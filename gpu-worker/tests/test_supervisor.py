import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from bangpick_worker.supervisor import local_processing_slot, supervise
from bangpick_worker.transfers import TransferError


class RecordingBackend:
    def __init__(self, fail_after=None):
        self.calls = []
        self.fail_after = fail_after

    def command(self, job_id, action, payload):
        self.calls.append((job_id, action, payload))
        if self.fail_after is not None and len(self.calls) > self.fail_after:
            raise TransferError("Heartbeat failed")
        return {"leaseExpiresAt": "2026-11-19T12:00:00Z"}


class SupervisorTests(unittest.TestCase):
    def test_long_process_renews_lease_and_hides_worker_token(self):
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            job_dir = work_dir / "job-1"
            job_dir.mkdir()
            (job_dir / "source.mp4").write_bytes(b"video")
            backend = RecordingBackend()
            command = [sys.executable, "-c", "import os,time; from pathlib import Path; "
                       "Path('token-present').write_text(str('BANGPICK_MEDIA_WORKER_TOKEN' in os.environ)); "
                       "time.sleep(0.25)"]
            with patch.dict(os.environ, {"BANGPICK_MEDIA_WORKER_TOKEN": "secret"}):
                result = supervise("job-1", "lease-1", work_dir, command, backend,
                                   heartbeat_interval=0.05)
            self.assertEqual(result, 0)
            self.assertEqual((job_dir / "token-present").read_text(), "False")
            self.assertGreaterEqual(len(backend.calls), 2)
            self.assertTrue(all(call == ("job-1", "heartbeat", {"leaseId": "lease-1"})
                                for call in backend.calls))

    def test_local_slot_rejects_second_process_and_releases_afterward(self):
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            with local_processing_slot(work_dir):
                with self.assertRaisesRegex(TransferError, "already processing"):
                    with local_processing_slot(work_dir):
                        pass
            with local_processing_slot(work_dir):
                pass

    def test_failed_heartbeat_stops_processing_command(self):
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            (work_dir / "job-1").mkdir()
            (work_dir / "job-1" / "source.mp4").write_bytes(b"video")
            backend = RecordingBackend(fail_after=1)
            with self.assertRaisesRegex(TransferError, "Heartbeat failed"):
                supervise("job-1", "lease-1", work_dir,
                          [sys.executable, "-c", "import time; time.sleep(10)"],
                          backend, heartbeat_interval=0.05)
            self.assertEqual(len(backend.calls), 2)

    def test_missing_command_or_download_does_not_claim_a_slot(self):
        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            backend = RecordingBackend()
            with self.assertRaisesRegex(TransferError, "Download the job"):
                supervise("job-1", "lease-1", work_dir, [sys.executable], backend)
            (work_dir / "job-1").mkdir()
            with self.assertRaisesRegex(TransferError, "Download the job"):
                supervise("job-1", "lease-1", work_dir, [sys.executable], backend)
            (work_dir / "job-1" / "source.mp4").write_bytes(b"video")
            with self.assertRaisesRegex(TransferError, "Specify a processing command"):
                supervise("job-1", "lease-1", work_dir, [], backend)
            self.assertEqual(backend.calls, [])


if __name__ == "__main__":
    unittest.main()
