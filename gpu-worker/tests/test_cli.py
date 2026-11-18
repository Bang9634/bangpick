from argparse import Namespace
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import uuid4

from bangpick_worker.__main__ import run
from bangpick_worker.transfers import TransferError


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
            def claim_next(self):
                return {"jobId": str(job_id), "leaseId": str(lease_id)}

            def command(self, actual_job_id, action, payload):
                raise TransferError("Backend unavailable")

        with TemporaryDirectory() as temporary:
            work_dir = Path(temporary)
            with self.assertRaises(TransferError):
                run(Namespace(action="download-next", work_dir=work_dir), FailingBackend())
            self.assertEqual((work_dir / str(job_id) / "lease").read_text(encoding="ascii"), str(lease_id))


if __name__ == "__main__":
    unittest.main()
