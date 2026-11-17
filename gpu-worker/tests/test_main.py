"""외부 통신과 GPU 연산을 재현해 Worker의 작업 흐름을 확인한다."""

from argparse import Namespace
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, call, patch
from uuid import uuid4

from bangpick_worker import __main__ as worker
from bangpick_worker.transfers import Transfers


class WorkerTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.job_id = uuid4()
        self.lease_id = str(uuid4())
        self.directory = self.root / str(self.job_id)
        self.model = self.directory / "colmap" / "sparse" / "1"
        self.ply = self.root / "room.ply"
        self.ply.write_bytes(b"ply\n")
        self.input_grant = {"url": "https://storage.example.test/source.mp4"}
        self.output_grant = {"url": "https://storage.example.test/room.ply"}
        self.transfers = Mock(spec=Transfers)
        self.transfers.command.side_effect = self.backend_response

        # 순서 검증을 위해 통신과 연산 호출을 한 기록에 모은다.
        self.history = Mock()
        self.history.attach_mock(self.transfers.command, "command")
        self.history.attach_mock(self.transfers.download, "download")
        self.history.attach_mock(self.transfers.upload, "upload")
        replacements = (
            ("extract_frames", "extract", 12),
            ("estimate_camera_poses", "poses", self.model),
            ("train_3dgs", "train", self.ply),
        )
        for name, label, result in replacements:
            replacement = patch.object(worker, name, return_value=result)
            mocked = replacement.start()
            self.addCleanup(replacement.stop)
            self.history.attach_mock(mocked, label)

        output = redirect_stdout(StringIO())
        output.__enter__()
        self.addCleanup(output.__exit__, None, None, None)

    def backend_response(self, job_id, action, payload):
        if action == "claim":
            return {
                "leaseId": self.lease_id,
                "extension": ".mp4",
                "transfer": self.input_grant,
            }
        if action == "result-upload":
            return self.output_grant
        return {"status": "RESULT_READY"}

    def arguments(self, action):
        return Namespace(
            action=action,
            job_id=self.job_id,
            work_dir=self.root,
            ply=self.ply,
        )

    def save_lease(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        (self.directory / "lease").write_text(self.lease_id, encoding="ascii")

    def test_reconstruct_passes_results_in_order_and_reports_completion(self):
        worker.run(self.arguments("reconstruct"), self.transfers)

        self.assertEqual(
            self.history.mock_calls,
            [
                call.command(str(self.job_id), "claim", {"leaseId": None}),
                call.download(self.input_grant, self.directory / "source.mp4"),
                call.extract(self.directory / "source.mp4", self.directory / "images"),
                call.poses(self.directory / "images", self.directory / "colmap"),
                call.train(self.directory / "images", self.model, self.directory / "3dgs"),
                call.command(
                    str(self.job_id),
                    "result-upload",
                    {"leaseId": self.lease_id, "sizeBytes": self.ply.stat().st_size},
                ),
                call.upload(self.output_grant, self.ply),
                call.command(
                    str(self.job_id),
                    "result-complete",
                    {"leaseId": self.lease_id},
                ),
            ],
        )

    def test_download_and_preprocess_stop_before_later_stages(self):
        for action in ("download", "preprocess"):
            with self.subTest(action=action):
                self.history.reset_mock()
                worker.run(self.arguments(action), self.transfers)
                self.assertEqual(
                    self.history.extract.call_count,
                    1 if action == "preprocess" else 0,
                )
                self.history.poses.assert_not_called()
                self.history.train.assert_not_called()
                self.transfers.upload.assert_not_called()
                self.assertEqual(self.transfers.command.call_count, 1)

    def test_processing_failure_prevents_upload_and_completion(self):
        cases = (
            ("extract", worker.PreprocessError),
            ("poses", worker.ColmapError),
            ("train", worker.TrainingError),
        )
        for stage, error_type in cases:
            with self.subTest(stage=stage):
                self.history.reset_mock()
                operation = getattr(self.history, stage)
                operation.side_effect = error_type("단계 실패")
                try:
                    with self.assertRaises(error_type):
                        worker.run(self.arguments("reconstruct"), self.transfers)
                finally:
                    operation.side_effect = None
                if stage == "extract":
                    self.history.poses.assert_not_called()
                if stage in {"extract", "poses"}:
                    self.history.train.assert_not_called()
                self.transfers.upload.assert_not_called()
                self.assertEqual(self.transfers.command.call_count, 1)

    def test_failed_download_preserves_lease_for_retry(self):
        self.transfers.download.side_effect = worker.TransferError("다운로드 실패")
        with self.assertRaises(worker.TransferError):
            worker.run(self.arguments("download"), self.transfers)
        self.assertEqual(
            (self.directory / "lease").read_text(encoding="ascii"),
            self.lease_id,
        )

        self.transfers.download.side_effect = None
        worker.run(self.arguments("download"), self.transfers)
        self.transfers.command.assert_called_with(
            str(self.job_id),
            "claim",
            {"leaseId": self.lease_id},
        )

    def test_upload_can_retry_without_repeating_training(self):
        self.save_lease()
        self.transfers.upload.side_effect = worker.TransferError("업로드 실패")
        with self.assertRaises(worker.TransferError):
            worker.run(self.arguments("upload"), self.transfers)
        self.assertEqual(self.transfers.command.call_count, 1)

        self.transfers.upload.side_effect = None
        worker.run(self.arguments("upload"), self.transfers)
        self.assertEqual(self.transfers.upload.call_count, 2)
        self.history.train.assert_not_called()
        self.assertEqual(
            [entry.args[1] for entry in self.transfers.command.call_args_list],
            ["result-upload", "result-upload", "result-complete"],
        )

    def test_complete_retries_only_report_after_failed_completion(self):
        self.save_lease()

        def fail_completion(job_id, action, payload):
            if action == "result-complete":
                raise worker.TransferError("완료 보고 실패")
            return self.backend_response(job_id, action, payload)

        self.transfers.command.side_effect = fail_completion
        with self.assertRaises(worker.TransferError):
            worker.run(self.arguments("upload"), self.transfers)

        self.transfers.command.side_effect = self.backend_response
        worker.run(self.arguments("complete"), self.transfers)
        self.transfers.upload.assert_called_once()
        self.assertEqual(
            [entry.args[1] for entry in self.transfers.command.call_args_list],
            ["result-upload", "result-complete", "result-complete"],
        )

    def test_upload_requires_lease_and_valid_ply_header(self):
        with self.assertRaises(worker.TransferError):
            worker.run(self.arguments("upload"), self.transfers)
        self.save_lease()
        self.ply.write_bytes(b"invalid\n")
        with self.assertRaises(worker.TransferError):
            worker.run(self.arguments("upload"), self.transfers)
        self.transfers.command.assert_not_called()


class WorkerCliTests(unittest.TestCase):
    def test_reconstruct_parses_job_and_work_directory(self):
        job_id = uuid4()
        arguments = [
            "bangpick-worker", "reconstruct", str(job_id),
            "--work-dir", "work",
        ]
        with patch("sys.argv", arguments):
            with patch.object(worker, "Transfers"):
                with patch.object(worker, "run") as run:
                    worker.main()
        parsed = run.call_args.args[0]
        self.assertEqual(parsed.action, "reconstruct")
        self.assertEqual(parsed.job_id, job_id)
        self.assertEqual(parsed.work_dir, Path("work"))

    def test_stage_errors_exit_with_failure_status_and_message(self):
        arguments = [
            "bangpick-worker", "reconstruct", str(uuid4()),
            "--work-dir", "work",
        ]
        for error_type in (
            worker.TransferError,
            worker.PreprocessError,
            worker.ColmapError,
            worker.TrainingError,
        ):
            with self.subTest(error=error_type.__name__):
                message = StringIO()
                with patch("sys.argv", arguments), redirect_stderr(message):
                    with patch.object(worker, "Transfers"):
                        with patch.object(
                            worker,
                            "run",
                            side_effect=error_type("실패 이유"),
                        ):
                            with self.assertRaises(SystemExit) as caught:
                                worker.main()
                self.assertEqual(caught.exception.code, 1)
                self.assertIn("실패 이유", message.getvalue())
