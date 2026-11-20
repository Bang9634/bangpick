"""전처리 실패 처리와 FFmpeg를 사용한 로컬 JPG 추출을 확인한다."""

from argparse import Namespace
from io import BytesIO
import json
from pathlib import Path
from shutil import which
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch
from uuid import uuid4

from bangpick_worker.__main__ import run
from bangpick_worker import preprocess
from bangpick_worker.preprocess import PreprocessError, extract_frames
from bangpick_worker.transfers import Transfers


class PreprocessFailureTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.video = self.root / "clip.mp4"
        self.video.touch()
        self.output = self.root / "images"

    def test_invalid_input_prevents_ffmpeg_execution(self):
        with patch.object(preprocess.subprocess, "run") as run:
            with self.assertRaises(PreprocessError):
                extract_frames(self.root / "missing.mp4", self.output)
            with self.assertRaises(PreprocessError):
                extract_frames(self.video, self.output, fps=0)
            run.assert_not_called()

    def test_empty_output_is_rejected_and_temporary_files_are_removed(self):
        with patch.object(preprocess.subprocess, "run"):
            with self.assertRaises(PreprocessError):
                extract_frames(self.video, self.output)
        self.assertEqual(list(self.root.iterdir()), [self.video])

    def test_requested_fps_and_source_path_reach_ffmpeg(self):
        def extract(command, **options):
            self.assertEqual(command[command.index("-i") + 1], str(self.video.resolve()))
            self.assertEqual(command[command.index("-vf") + 1], "fps=8")
            frame = Path(options["cwd"]) / "frame_000001.jpg"
            frame.write_bytes(b"test frame")

        with patch.object(preprocess.subprocess, "run", side_effect=extract):
            self.assertEqual(extract_frames(self.video, self.output, fps=8), 1)
        self.assertEqual(
            (self.output / "frame_000001.jpg").read_bytes(),
            b"test frame",
        )
        self.assertEqual(list(self.root.glob(".preprocess-*")), [])

    def test_ffmpeg_errors_are_reported_and_partial_frames_are_removed(self):
        errors = (
            FileNotFoundError(),
            subprocess.TimeoutExpired("ffmpeg", 1800),
            subprocess.CalledProcessError(1, "ffmpeg", stderr=b"decode failed"),
        )
        for error in errors:
            with self.subTest(error=type(error).__name__):
                def fail(command, **options):
                    partial = Path(options["cwd"]) / "frame_000001.jpg"
                    partial.write_bytes(b"partial")
                    raise error

                with patch.object(preprocess.subprocess, "run", side_effect=fail):
                    with self.assertRaises(PreprocessError):
                        extract_frames(self.video, self.output)
                self.assertEqual(list(self.root.iterdir()), [self.video])


@unittest.skipUnless(which("ffmpeg"), "실제 추출 테스트에는 FFmpeg가 필요합니다")
class PreprocessIntegrationTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def make_video(self) -> Path:
        video = self.directory / "clip.mp4"
        subprocess.run(
            [
                "ffmpeg", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=blue:s=96x64:r=12:d=2",
                "-c:v", "mpeg4", str(video),
            ],
            check=True,
        )
        return video

    def assert_frame_sequence(self, output: Path):
        names = sorted(path.name for path in output.iterdir())
        self.assertEqual(
            names,
            [f"frame_{number:06d}.jpg" for number in range(1, 13)],
        )
        self.assertEqual((output / names[0]).read_bytes()[:2], b"\xff\xd8")

    def test_extracts_frames_from_local_video(self):
        video = self.make_video()
        output = self.directory / "local" / "images"

        self.assertEqual(extract_frames(video, output), 12)
        self.assert_frame_sequence(output)
        with self.assertRaisesRegex(PreprocessError, "이미 존재"):
            extract_frames(video, output)
        self.assert_frame_sequence(output)

    def test_worker_downloads_source_and_extracts_from_the_downloaded_file(self):
        video = self.make_video().read_bytes()
        job_id = uuid4()
        lease_id = str(uuid4())
        url = "https://storage.example.test/source.mp4"
        claim = {
            "leaseId": lease_id,
            "extension": ".mp4",
            "transfer": {"method": "GET", "sizeBytes": len(video), "url": url},
        }
        transfers = Transfers("http://127.0.0.1:8080/api", "t" * 32)
        transfers.opener = Mock()
        transfers.opener.open.side_effect = [
            BytesIO(json.dumps(claim).encode()),
            BytesIO(video),
        ]

        run(
            Namespace(action="preprocess", job_id=job_id, work_dir=self.directory),
            transfers,
        )

        claim_request, source_request = [
            call.args[0] for call in transfers.opener.open.call_args_list
        ]
        self.assertTrue(claim_request.full_url.endswith(f"/{job_id}/claim"))
        self.assertEqual(source_request.full_url, url)
        self.assertFalse(source_request.has_header("Authorization"))
        job_dir = self.directory / str(job_id)
        self.assertEqual((job_dir / "source.mp4").read_bytes(), video)
        self.assertEqual((job_dir / "lease").read_text(), lease_id)
        self.assert_frame_sequence(job_dir / "images")

    def test_failed_decode_leaves_no_output_and_allows_retry(self):
        video = self.directory / "clip.mp4"
        video.write_bytes(b"corrupt video")
        output = self.directory / "images"

        with self.assertRaisesRegex(PreprocessError, "FFmpeg 프레임 추출 실패"):
            extract_frames(video, output)
        self.assertEqual(list(self.directory.iterdir()), [video])
        video.unlink()
        self.assertEqual(extract_frames(self.make_video(), output), 12)
