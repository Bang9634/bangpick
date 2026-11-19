from argparse import Namespace
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from uuid import uuid4

from bangpick_worker import __main__ as cli
from bangpick_worker.__main__ import run
from bangpick_worker.supervisor import local_processing_slot
from bangpick_worker.transfers import TransferError


class Backend:
    def __init__(self):
        self.job_id = str(uuid4())
        self.lease = str(uuid4())
        self.actions = []
        self.claim_next_calls = 0
        self.uploaded = None
        self.fail_upload = False
        self.fail_completion = False
        self.on_upload = None

    def claim_next(self):
        self.claim_next_calls += 1
        return {"jobId": self.job_id, "leaseId": self.lease}

    def command(self, job_id, action, payload):
        self.actions.append((action, payload))
        if action == "claim":
            return {"leaseId": self.lease, "extension": ".mp4", "transfer": {}}
        if action == "heartbeat":
            return {"leaseExpiresAt": "2026-11-19T12:00:00Z"}
        if action == "result-upload":
            return {"sizeBytes": payload["sizeBytes"]}
        if action == "result-complete":
            if self.fail_completion:
                raise TransferError("Completion unavailable")
            return {"status": "RESULT_READY"}
        if action == "processing-failed":
            return {"status": "FAILED"}
        raise AssertionError(f"Unexpected action: {action}")

    def download(self, grant, target):
        target.write_bytes(b"video")

    def upload(self, grant, path):
        if self.on_upload is not None:
            self.on_upload()
        if self.fail_upload:
            raise TransferError("Upload unavailable")
        self.uploaded = (path, path.read_bytes())


def process_args(work_dir: Path, command: list[str], retryable: bool = False) -> Namespace:
    return Namespace(action="process-next", work_dir=work_dir, processor=command, retryable=retryable)


def write_result_command() -> list[str]:
    return [sys.executable, "-c", "import os; from pathlib import Path; "
            "assert Path(os.environ['BANGPICK_INPUT_VIDEO']).read_bytes() == b'video'; "
            "Path(os.environ['BANGPICK_OUTPUT_PLY']).write_bytes(b'ply\\nend_header\\n')"]


class ProcessNextTests(unittest.TestCase):
    def test_cli_passes_processor_command_after_separator(self):
        backend = Backend()
        with TemporaryDirectory() as temporary:
            arguments = ["bangpick-worker", "process-next", "--work-dir", temporary,
                         "--", *write_result_command()]
            with patch.object(sys, "argv", arguments), patch.object(cli, "Transfers", return_value=backend):
                cli.main()
            self.assertIsNotNone(backend.uploaded)

    def test_one_command_claims_processes_and_records_result(self):
        backend = Backend()
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            job_dir = work_dir / backend.job_id
            job_dir.mkdir()
            (job_dir / "room.ply").write_bytes(b"old result")
            run(process_args(work_dir, write_result_command()), backend)
            expected = job_dir / "attempts" / backend.lease / "room.ply"
            self.assertEqual(backend.uploaded, (expected, b"ply\nend_header\n"))
            self.assertEqual((job_dir / "lease").read_text(encoding="ascii"), backend.lease)
            self.assertEqual((job_dir / "room.ply").read_bytes(), b"old result")
        self.assertEqual(backend.claim_next_calls, 1)
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "result-upload", "result-complete"])

    def test_slot_is_held_before_claiming(self):
        backend = Backend()
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            with local_processing_slot(work_dir):
                with self.assertRaisesRegex(TransferError, "already processing"):
                    run(process_args(work_dir, write_result_command()), backend)
                with self.assertRaisesRegex(TransferError, "already processing"):
                    run(Namespace(action="download-next", work_dir=work_dir), backend)
        self.assertEqual(backend.claim_next_calls, 0)

    def test_slot_stays_held_during_upload_and_releases_after_completion(self):
        backend = Backend()
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)

            def assert_locked():
                with self.assertRaisesRegex(TransferError, "already processing"):
                    with local_processing_slot(work_dir):
                        pass

            backend.on_upload = assert_locked
            run(process_args(work_dir, write_result_command()), backend)
            with local_processing_slot(work_dir):
                pass

    def test_empty_queue_does_not_start_processor(self):
        class EmptyBackend:
            def claim_next(self):
                return None

        with TemporaryDirectory() as temporary:
            run(process_args(Path(temporary), ["missing-processor"]), EmptyBackend())
            self.assertEqual([path for path in Path(temporary).iterdir() if path.is_dir()], [])

    def test_missing_result_reports_processing_failure_before_upload(self):
        backend = Backend()
        with TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(TransferError, "must be a PLY"):
                run(process_args(Path(temporary), [sys.executable, "-c", "pass"]), backend)
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "processing-failed"])

    def test_nonzero_processor_exit_reports_retryable_failure(self):
        backend = Backend()
        with TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(TransferError, "exited with code 7"):
                run(process_args(Path(temporary),
                                 [sys.executable, "-c", "import sys; sys.exit(7)"], retryable=True), backend)
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "processing-failed"])
        self.assertEqual(backend.actions[-1][1], {"leaseId": backend.lease, "retryable": True})

    def test_processor_start_failure_is_reported(self):
        backend = Backend()
        with TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(TransferError, "Could not start"):
                run(process_args(Path(temporary), ["missing-processor"]), backend)
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "processing-failed"])
        self.assertEqual(backend.actions[-1][1], {"leaseId": backend.lease, "retryable": False})

    def test_upload_failure_keeps_lease_and_does_not_requeue(self):
        backend = Backend()
        backend.fail_upload = True
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            with self.assertRaisesRegex(TransferError, "retry upload"):
                run(process_args(work_dir, write_result_command()), backend)
            self.assertEqual((work_dir / backend.job_id / "lease").read_text(encoding="ascii"), backend.lease)
            self.assertTrue((work_dir / backend.job_id / "attempts" / backend.lease / "room.ply").is_file())
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "result-upload"])

    def test_completion_failure_keeps_lease_for_completion_retry(self):
        backend = Backend()
        backend.fail_completion = True
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            with self.assertRaisesRegex(TransferError, "retry complete"):
                run(process_args(work_dir, write_result_command()), backend)
            self.assertEqual((work_dir / backend.job_id / "lease").read_text(encoding="ascii"), backend.lease)
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "result-upload", "result-complete"])


if __name__ == "__main__":
    unittest.main()
