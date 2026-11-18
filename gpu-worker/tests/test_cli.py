from argparse import Namespace
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from uuid import uuid4

from bangpick_worker.__main__ import run
from bangpick_worker.supervisor import ProcessStartError
from bangpick_worker.transfers import PermanentTransferError, TransferError


class DownloadNextTests(unittest.TestCase):
    def test_download_next_saves_lease_before_requesting_input_url(self):
        job_id = uuid4()
        lease_id = uuid4()

        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            job_dir = work_dir / str(job_id)

            class WorkerBackend:
                def claim_next(self):
                    return {"jobId": str(job_id), "leaseId": str(lease_id)}

                def command(self, actual_job_id, action, payload):
                    self_call = (actual_job_id, action, payload)
                    assert self_call == (str(job_id), "claim", {"leaseId": str(lease_id)})
                    assert (job_dir / "lease").read_text(encoding="ascii") == str(lease_id)
                    return {"leaseId": str(lease_id), "extension": ".mp4", "transfer": {}}

                def download(self, grant, target):
                    target.write_bytes(b"video")

            run(Namespace(action="download-next", work_dir=work_dir), WorkerBackend())
            self.assertEqual((job_dir / "source.mp4").read_bytes(), b"video")

    def test_download_next_exits_without_a_job(self):
        class EmptyBackend:
            def claim_next(self):
                return None

            def command(self, job_id, action, payload):
                raise AssertionError("No job should be claimed")

        with TemporaryDirectory() as temporary:
            run(Namespace(action="download-next", work_dir=Path(temporary)), EmptyBackend())
            self.assertEqual(list(Path(temporary).iterdir()), [])

    def test_download_next_keeps_lease_when_input_url_request_fails(self):
        job_id = uuid4()
        lease_id = uuid4()

        class FailingBackend:
            def __init__(self):
                self.actions = []

            def claim_next(self):
                return {"jobId": str(job_id), "leaseId": str(lease_id)}

            def command(self, actual_job_id, action, payload):
                self.actions.append(action)
                raise TransferError("Backend unavailable")

        backend = FailingBackend()
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            with self.assertRaisesRegex(TransferError, "did not confirm the failure"):
                run(Namespace(action="download-next", work_dir=work_dir), backend)
            self.assertEqual((work_dir / str(job_id) / "lease").read_text(encoding="ascii"), str(lease_id))
        self.assertEqual(backend.actions, ["claim", "input-failed"])

    def test_download_failure_reports_retryable_or_permanent_error(self):
        for error, retryable in ((TransferError("Download interrupted"), True),
                                 (PermanentTransferError("Invalid grant"), False)):
            with self.subTest(retryable=retryable), TemporaryDirectory() as temporary:
                job_id = uuid4()
                lease_id = uuid4()

                class FailingDownload:
                    def __init__(self):
                        self.report = None

                    def claim_next(self):
                        return {"jobId": str(job_id), "leaseId": str(lease_id)}

                    def command(self, actual_job_id, action, payload):
                        if action == "input-failed":
                            self.report = (actual_job_id, payload)
                            return {"status": "QUEUED" if retryable else "FAILED"}
                        return {"leaseId": str(lease_id), "extension": ".mp4", "transfer": {}}

                    def download(self, grant, target):
                        raise error

                backend = FailingDownload()
                with self.assertRaises(TransferError):
                    run(Namespace(action="download-next", work_dir=Path(temporary)), backend)
                self.assertEqual(backend.report,
                                 (str(job_id), {"leaseId": str(lease_id), "retryable": retryable}))


class HeartbeatTests(unittest.TestCase):
    def test_heartbeat_uses_saved_lease(self):
        job_id = uuid4()
        lease_id = uuid4()

        class HeartbeatBackend:
            def __init__(self):
                self.request = None

            def command(self, actual_job_id, action, payload):
                self.request = (actual_job_id, action, payload)
                return {"leaseExpiresAt": "2026-11-19T12:00:00Z"}

        backend = HeartbeatBackend()
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            job_dir = work_dir / str(job_id)
            job_dir.mkdir()
            (job_dir / "lease").write_text(str(lease_id), encoding="ascii")
            run(Namespace(action="heartbeat", job_id=job_id, work_dir=work_dir), backend)
        self.assertEqual(backend.request, (str(job_id), "heartbeat", {"leaseId": str(lease_id)}))

    def test_heartbeat_requires_a_saved_lease(self):
        with TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(TransferError, "Run download"):
                run(Namespace(action="heartbeat", job_id=uuid4(), work_dir=Path(temporary)), object())


class RunProcessTests(unittest.TestCase):
    def test_run_process_uses_saved_lease_and_passes_command(self):
        job_id = uuid4()
        lease_id = uuid4()
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            job_dir = work_dir / str(job_id)
            job_dir.mkdir()
            (job_dir / "lease").write_text(str(lease_id), encoding="ascii")
            command = ["python", "processor.py"]
            with patch("bangpick_worker.__main__.supervise", return_value=0) as supervisor:
                run(Namespace(action="run-process", job_id=job_id, work_dir=work_dir,
                              processor=command, retryable=False), object())
            supervisor.assert_called_once_with(str(job_id), str(lease_id), work_dir,
                                               command, unittest.mock.ANY)

    def test_run_process_reports_nonzero_exit_as_retryable(self):
        job_id = uuid4()
        lease_id = uuid4()
        class FailureBackend:
            def __init__(self):
                self.calls = []

            def command(self, actual_job_id, action, payload):
                self.calls.append((actual_job_id, action, payload))
                return {"status": "QUEUED"}

        backend = FailureBackend()
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            job_dir = work_dir / str(job_id)
            job_dir.mkdir()
            (job_dir / "lease").write_text(str(lease_id), encoding="ascii")
            with patch("bangpick_worker.__main__.supervise", return_value=2):
                with self.assertRaisesRegex(TransferError, "exited with code 2"):
                    run(Namespace(action="run-process", job_id=job_id, work_dir=work_dir,
                                  processor=["python"], retryable=True), backend)
        self.assertEqual(backend.calls, [(str(job_id), "processing-failed",
                                          {"leaseId": str(lease_id), "retryable": True})])

    def test_start_failure_reports_permanent_failure(self):
        job_id = uuid4()
        lease_id = uuid4()
        calls = []

        class FailureBackend:
            def command(self, actual_job_id, action, payload):
                calls.append((actual_job_id, action, payload))
                return {"status": "FAILED"}

        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            job_dir = work_dir / str(job_id)
            job_dir.mkdir()
            (job_dir / "lease").write_text(str(lease_id), encoding="ascii")
            with patch("bangpick_worker.__main__.supervise", side_effect=ProcessStartError("No processor")):
                with self.assertRaisesRegex(ProcessStartError, "No processor"):
                    run(Namespace(action="run-process", job_id=job_id, work_dir=work_dir,
                                  processor=["missing"], retryable=False), FailureBackend())
        self.assertEqual(calls, [(str(job_id), "processing-failed",
                                  {"leaseId": str(lease_id), "retryable": False})])

    def test_failed_report_can_be_retried_without_rerunning_command(self):
        job_id = uuid4()
        lease_id = uuid4()

        class FailureBackend:
            def command(self, actual_job_id, action, payload):
                self.request = (actual_job_id, action, payload)
                return {"status": "FAILED"}

        backend = FailureBackend()
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            job_dir = work_dir / str(job_id)
            job_dir.mkdir()
            (job_dir / "lease").write_text(str(lease_id), encoding="ascii")
            run(Namespace(action="processing-failed", job_id=job_id, work_dir=work_dir,
                          retryable=False), backend)
        self.assertEqual(backend.request, (str(job_id), "processing-failed",
                                           {"leaseId": str(lease_id), "retryable": False}))

    def test_unconfirmed_processing_failure_keeps_lease_for_report_retry(self):
        job_id = uuid4()
        lease_id = uuid4()

        class UnavailableBackend:
            def command(self, actual_job_id, action, payload):
                raise TransferError("Backend unavailable")

        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            job_dir = work_dir / str(job_id)
            job_dir.mkdir()
            lease_file = job_dir / "lease"
            lease_file.write_text(str(lease_id), encoding="ascii")
            with patch("bangpick_worker.__main__.supervise", return_value=2):
                with self.assertRaisesRegex(TransferError, "did not confirm the failure"):
                    run(Namespace(action="run-process", job_id=job_id, work_dir=work_dir,
                                  processor=["python"], retryable=False), UnavailableBackend())
            self.assertEqual(lease_file.read_text(encoding="ascii"), str(lease_id))

    def test_unknown_heartbeat_failure_does_not_requeue_job(self):
        job_id = uuid4()
        lease_id = uuid4()

        class RecordingBackend:
            def __init__(self):
                self.calls = []

            def command(self, actual_job_id, action, payload):
                self.calls.append(action)
                return {"status": "QUEUED"}

        backend = RecordingBackend()
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            job_dir = work_dir / str(job_id)
            job_dir.mkdir()
            (job_dir / "lease").write_text(str(lease_id), encoding="ascii")
            with patch("bangpick_worker.__main__.supervise", side_effect=TransferError("Heartbeat failed")):
                with self.assertRaisesRegex(TransferError, "Heartbeat failed"):
                    run(Namespace(action="run-process", job_id=job_id, work_dir=work_dir,
                                  processor=["python"], retryable=True), backend)
        self.assertEqual(backend.calls, [])


if __name__ == "__main__":
    unittest.main()
