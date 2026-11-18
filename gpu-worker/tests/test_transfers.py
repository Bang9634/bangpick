from io import BytesIO
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from bangpick_worker.transfers import TransferError, Transfers


class FixtureOpener:
    def __init__(self, body):
        self.body = body
        self.uploaded = None

    def open(self, request, timeout):
        if request.get_method() == "PUT":
            self.uploaded = request.data.read()
            if int(request.get_header("Content-length")) != len(self.uploaded):
                raise AssertionError("wrong upload length")
        if request.has_header("Authorization"):
            raise AssertionError("worker credential sent to storage")
        return BytesIO(self.body)


class BackendResponse(BytesIO):
    def __init__(self, status, body=b""):
        super().__init__(body)
        self.status = status


class QueueOpener:
    def __init__(self, response):
        self.response = response
        self.request = None

    def open(self, request, timeout):
        self.request = request
        return self.response


class TransferTests(unittest.TestCase):
    def setUp(self):
        self.client = Transfers("http://127.0.0.1:8080/api", "t" * 32)
        self.url = "https://storage.example.test/scoped-object"

    def grant(self, method, size):
        return {"method": method, "sizeBytes": size, "url": self.url}

    def test_claim_next_returns_none_for_empty_queue_and_reads_job_when_available(self):
        empty = QueueOpener(BackendResponse(204))
        self.client.opener = empty
        self.assertIsNone(self.client.claim_next())
        self.assertEqual(empty.request.full_url, "http://127.0.0.1:8080/api/internal/media/jobs/claim-next")
        self.assertEqual(empty.request.get_method(), "POST")
        self.assertEqual(empty.request.get_header("Authorization"), "Bearer " + "t" * 32)

        job = {"jobId": "job", "leaseId": "lease"}
        available = QueueOpener(BackendResponse(200, json.dumps(job).encode()))
        self.client.opener = available
        self.assertEqual(self.client.claim_next(), job)

    def test_download_requires_exact_size_before_replacing_file(self):
        with TemporaryDirectory() as directory:
            target = Path(directory) / "video"
            self.client.opener = FixtureOpener(b"video")
            self.client.download(self.grant("GET", 5), target)
            self.assertEqual(target.read_bytes(), b"video")
            for body in (b"bad", b"too-long"):
                self.client.opener = FixtureOpener(body)
                with self.assertRaises(TransferError):
                    self.client.download(self.grant("GET", 5), target)
                self.assertEqual(target.read_bytes(), b"video")
                self.assertEqual(list(Path(directory).iterdir()), [target])

    def test_upload_sends_exact_file_without_backend_credential(self):
        with TemporaryDirectory() as directory:
            target = Path(directory) / "room.ply"
            target.write_bytes(b"ply\n")
            fixture = FixtureOpener(b"")
            self.client.opener = fixture
            self.client.upload(self.grant("PUT", 4), target)
            self.assertEqual(fixture.uploaded, b"ply\n")
            with self.assertRaises(TransferError):
                self.client.upload(self.grant("PUT", 5), target)

    def test_non_loopback_api_and_object_require_tls(self):
        with self.assertRaises(TransferError):
            Transfers("http://backend.example.test/api", "t" * 32)
        with TemporaryDirectory() as directory:
            with self.assertRaisesRegex(TransferError, "Invalid object transfer grant"):
                self.client.download({"method": "GET", "sizeBytes": 3, "url": "http://storage.example.test/key"},
                                     Path(directory) / "video")
