from argparse import Namespace
from functools import partial
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import time
import unittest
from unittest.mock import patch
from uuid import uuid4

from bangpick_worker import __main__ as cli
from bangpick_worker.__main__ import ProcessNextResult, ReportedJobFailure, process_next, run
from bangpick_worker.supervisor import local_processing_slot
from bangpick_worker.transfers import TransferError


class Backend:
    def __init__(self):
        self.job_id = str(uuid4())
        self.lease = str(uuid4())
        self.actions = []
        self.claim_next_calls = 0
        self.fail_claim = False
        self.uploaded = None
        self.fail_upload = False
        self.fail_completion = False
        self.fail_download = False
        self.fail_heartbeat = False
        self.fail_failure_report = False
        self.on_upload = None

    def claim_next(self):
        self.claim_next_calls += 1
        if self.fail_claim:
            raise TransferError("Claim response unavailable")
        return {"jobId": self.job_id, "leaseId": self.lease}

    def command(self, job_id, action, payload):
        self.actions.append((action, payload))
        if action == "claim":
            return {"leaseId": self.lease, "extension": ".mp4", "transfer": {}}
        if action == "heartbeat":
            if self.fail_heartbeat:
                raise TransferError("Heartbeat unavailable")
            return {"leaseExpiresAt": "2026-11-19T12:00:00Z"}
        if action == "result-upload":
            return {"sizeBytes": payload["sizeBytes"]}
        if action == "result-complete":
            if self.fail_completion:
                raise TransferError("Completion unavailable")
            return {"status": "RESULT_READY"}
        if action in {"input-failed", "processing-failed"}:
            if self.fail_failure_report:
                raise TransferError("Failure report unavailable")
            return {"status": "QUEUED" if payload["retryable"] else "FAILED"}
        raise AssertionError(f"Unexpected action: {action}")

    def download(self, grant, target):
        if self.fail_download:
            raise TransferError("Download unavailable")
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
            result = process_next(process_args(work_dir, write_result_command()), backend)
            self.assertEqual(result, ProcessNextResult.COMPLETED)
            expected = job_dir / "attempts" / backend.lease / "room.ply"
            self.assertEqual(backend.uploaded, (expected, b"ply\nend_header\n"))
            self.assertEqual((job_dir / "lease").read_text(encoding="ascii"), backend.lease)
            self.assertEqual((job_dir / "room.ply").read_bytes(), b"old result")
        self.assertEqual(backend.claim_next_calls, 1)
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "result-upload", "result-complete"])

    def test_build_3d_scene_reaches_preprocessing_and_reports_failure(self):
        backend = Backend()
        source_dir = Path(__file__).resolve().parents[1] / "src"
        environment = {"PYTHONPATH": str(source_dir), "PYTHONIOENCODING": "utf-8"}
        command = [sys.executable, "-m", "bangpick_worker", "build-3d-scene"]

        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            attempt_dir = work_dir / backend.job_id / "attempts" / backend.lease
            # 입력 영상 경로를 확인한 뒤 FFmpeg를 시작하기 전에 전처리가 실패하도록 한다.
            (attempt_dir / "images").mkdir(parents=True)

            stderr_path = work_dir / "child-stderr.txt"
            with stderr_path.open("wb") as stderr, patch.dict(os.environ, environment):
                start_child = partial(subprocess.Popen, stderr=stderr)
                with patch("bangpick_worker.supervisor.subprocess.Popen", new=start_child):
                    with self.assertRaises(ReportedJobFailure) as caught:
                        process_next(process_args(work_dir, command), backend)

            self.assertIn("프레임 출력 디렉터리가 이미 존재합니다", stderr_path.read_text(encoding="utf-8"))
            self.assertFalse((attempt_dir / "room.ply").exists())

        self.assertEqual(caught.exception.status, "FAILED")
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "processing-failed"])
        self.assertIsNone(backend.uploaded)

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

    def test_other_process_holding_same_work_dir_blocks_claim(self):
        backend = Backend()
        holder_code = """
from pathlib import Path
import sys
from bangpick_worker.supervisor import local_processing_slot
with local_processing_slot(Path(sys.argv[1])):
    Path(sys.argv[2]).write_text('locked', encoding='ascii')
    sys.stdin.read(1)
"""
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            ready = work_dir / "holder-ready"
            environment = os.environ.copy()
            source_dir = Path(__file__).resolve().parents[1] / "src"
            environment["PYTHONPATH"] = str(source_dir) + os.pathsep + environment.get("PYTHONPATH", "")
            holder = subprocess.Popen(
                [sys.executable, "-c", holder_code, str(work_dir), str(ready)],
                stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                text=True, env=environment,
            )
            try:
                deadline = time.monotonic() + 5
                while not ready.is_file() and holder.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(ready.is_file(), "Separate process did not acquire the slot")
                with self.assertRaisesRegex(TransferError, "already processing"):
                    process_next(process_args(work_dir, write_result_command()), backend)
                self.assertEqual(backend.claim_next_calls, 0)
            finally:
                if holder.poll() is None:
                    holder.stdin.close()
                    try:
                        holder.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        holder.kill()
                        holder.wait()
                holder.stderr.close()
            self.assertEqual(holder.returncode, 0)
            with local_processing_slot(work_dir):
                pass

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
            result = process_next(process_args(Path(temporary), ["missing-processor"]), EmptyBackend())
            self.assertEqual(result, ProcessNextResult.NO_JOB)
            self.assertEqual([path for path in Path(temporary).iterdir() if path.is_dir()], [])

    def test_missing_result_reports_processing_failure_before_upload(self):
        backend = Backend()
        with TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ReportedJobFailure, "must be a PLY") as caught:
                run(process_args(Path(temporary), [sys.executable, "-c", "pass"]), backend)
        self.assertEqual(caught.exception.status, "FAILED")
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "processing-failed"])

    def test_nonzero_processor_exit_reports_retryable_failure(self):
        backend = Backend()
        with TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ReportedJobFailure, "exited with code 7") as caught:
                run(process_args(Path(temporary),
                                 [sys.executable, "-c", "import sys; sys.exit(7)"], retryable=True), backend)
        self.assertEqual(caught.exception.status, "QUEUED")
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "processing-failed"])
        self.assertEqual(backend.actions[-1][1], {"leaseId": backend.lease, "retryable": True})

    def test_processor_start_failure_is_reported(self):
        backend = Backend()
        with TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ReportedJobFailure, "Could not start"):
                run(process_args(Path(temporary), ["missing-processor"]), backend)
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "processing-failed"])
        self.assertEqual(backend.actions[-1][1], {"leaseId": backend.lease, "retryable": False})

    def test_upload_failure_keeps_lease_and_does_not_requeue(self):
        backend = Backend()
        backend.fail_upload = True
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            with self.assertRaisesRegex(TransferError, "retry upload") as caught:
                run(process_args(work_dir, write_result_command()), backend)
            self.assertNotIsInstance(caught.exception, ReportedJobFailure)
            self.assertEqual((work_dir / backend.job_id / "lease").read_text(encoding="ascii"), backend.lease)
            self.assertTrue((work_dir / backend.job_id / "attempts" / backend.lease / "room.ply").is_file())
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "result-upload"])

    def test_completion_failure_keeps_lease_for_completion_retry(self):
        backend = Backend()
        backend.fail_completion = True
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            with self.assertRaisesRegex(TransferError, "retry complete") as caught:
                run(process_args(work_dir, write_result_command()), backend)
            self.assertNotIsInstance(caught.exception, ReportedJobFailure)
            self.assertEqual((work_dir / backend.job_id / "lease").read_text(encoding="ascii"), backend.lease)
        self.assertEqual([action for action, _ in backend.actions],
                         ["claim", "heartbeat", "result-upload", "result-complete"])

    def test_input_failure_is_reported_before_continuing(self):
        backend = Backend()
        backend.fail_download = True
        with TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ReportedJobFailure, "Download unavailable") as caught:
                process_next(process_args(Path(temporary), write_result_command()), backend)
        self.assertEqual((caught.exception.job_id, caught.exception.status), (backend.job_id, "QUEUED"))
        self.assertEqual([action for action, _ in backend.actions], ["claim", "input-failed"])

    def test_unconfirmed_failure_report_is_not_safe_to_continue(self):
        backend = Backend()
        backend.fail_download = True
        backend.fail_failure_report = True
        with TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(TransferError, "did not confirm") as caught:
                process_next(process_args(Path(temporary), write_result_command()), backend)
        self.assertNotIsInstance(caught.exception, ReportedJobFailure)
        self.assertEqual([action for action, _ in backend.actions], ["claim", "input-failed"])

    def test_unconfirmed_heartbeat_is_not_safe_to_continue(self):
        backend = Backend()
        backend.fail_heartbeat = True
        with TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(TransferError, "Heartbeat unavailable") as caught:
                process_next(process_args(Path(temporary), write_result_command()), backend)
        self.assertNotIsInstance(caught.exception, ReportedJobFailure)
        self.assertEqual([action for action, _ in backend.actions], ["claim", "heartbeat"])

    def test_unconfirmed_claim_response_is_not_safe_to_continue(self):
        backend = Backend()
        backend.fail_claim = True
        with TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(TransferError, "Claim response unavailable") as caught:
                process_next(process_args(Path(temporary), write_result_command()), backend)
        self.assertNotIsInstance(caught.exception, ReportedJobFailure)
        self.assertEqual(backend.claim_next_calls, 1)
        self.assertEqual(backend.actions, [])


if __name__ == "__main__":
    unittest.main()
