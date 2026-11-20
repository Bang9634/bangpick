"""GPU 실행을 재현해 학습 설정 전달과 체크포인트·PLY 검사를 확인한다."""

from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from bangpick_worker import train_3dgs as training


class TrainingTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.images = self.root / "images"
        self.images.mkdir()
        (self.images / "frame_000001.jpg").touch()
        self.model = self.root / "colmap" / "sparse" / "1"
        self.model.mkdir(parents=True)
        for filename in ("cameras.bin", "images.bin", "points3D.bin"):
            (self.model / filename).touch()
        self.output = self.root / "3dgs"
        self.write_checkpoint = True
        self.write_config = True
        self.write_export = True

        executable = patch.object(
            training.shutil,
            "which",
            side_effect=lambda name: name,
        )
        executable.start()
        self.addCleanup(executable.stop)
        runner = patch.object(
            training,
            "_run_nerfstudio",
            side_effect=self.simulate_nerfstudio,
        )
        self.runner = runner.start()
        self.addCleanup(runner.stop)

    def write_ply(self, path, *, vertices=2, omit_property=None):
        """검증에 필요한 최소 가우시안 PLY를 만든다."""
        properties = [
            "x", "y", "z", "opacity",
            "f_dc_0", "f_dc_1", "f_dc_2",
            "scale_0", "scale_1", "scale_2",
            "rot_0", "rot_1", "rot_2", "rot_3",
        ]
        if omit_property is not None:
            properties.remove(omit_property)
        header = [
            "ply",
            "format binary_little_endian 1.0",
            f"element vertex {vertices}",
        ]
        header.extend(f"property float {name}" for name in properties)
        header.append("end_header")
        body = bytes(vertices * len(properties) * 4)
        path.write_bytes(("\n".join(header) + "\n").encode() + body)

    def simulate_nerfstudio(self, command, stage):
        if stage == "학습":
            runs = Path(command[command.index("--output-dir") + 1])
            run = runs / "scene" / "splatfacto" / "train"
            checkpoints = run / "nerfstudio_models"
            checkpoints.mkdir(parents=True)
            if self.write_config:
                (run / "config.yml").write_text("# 테스트 설정\n", encoding="utf-8")
            if self.write_checkpoint:
                (checkpoints / "step-000019999.ckpt").touch()
        else:
            config = Path(command[command.index("--load-config") + 1])
            self.assertTrue(config.is_file())
            export = Path(command[command.index("--output-dir") + 1])
            export.mkdir()
            if self.write_export:
                filename = command[command.index("--output-filename") + 1]
                self.write_ply(export / filename)

    def test_trains_selected_model_for_20000_steps_and_exports_valid_ply(self):
        ply = training.train_3dgs(self.images, self.model, self.output)

        self.assertEqual(ply, self.output / "room.ply")
        training._validate_gaussian_ply(ply)
        calls = self.runner.call_args_list
        self.assertEqual([call.args[1] for call in calls], ["학습", "PLY 내보내기"])
        command = calls[0].args[0]
        self.assertEqual(command[command.index("--max-num-iterations") + 1], "20000")
        self.assertEqual(
            command[command.index("--colmap-path") + 1],
            str(self.model.resolve()),
        )
        self.assertEqual(
            command[command.index("--images-path") + 1],
            str(self.images.resolve()),
        )
        self.assertEqual(list(self.output.iterdir()), [ply])
        self.assertEqual(list(self.root.glob(".3dgs-*")), [])

    def test_can_override_training_steps(self):
        training.train_3dgs(self.images, self.model, self.output, max_iterations=2)
        command = self.runner.call_args_list[0].args[0]
        self.assertEqual(command[command.index("--max-num-iterations") + 1], "2")

    def test_missing_config_or_checkpoint_prevents_export(self):
        for missing in ("config", "checkpoint"):
            with self.subTest(missing=missing):
                self.write_config = missing != "config"
                self.write_checkpoint = missing != "checkpoint"
                self.runner.reset_mock()
                with self.assertRaises(training.TrainingError):
                    training.train_3dgs(self.images, self.model, self.output)
                self.assertEqual(self.runner.call_count, 1)
                self.assertFalse(self.output.exists())
                self.assertEqual(list(self.root.glob(".3dgs-*")), [])

    def test_failed_training_cleans_temporary_files(self):
        self.runner.side_effect = training.TrainingError("학습 실패")
        with self.assertRaises(training.TrainingError):
            training.train_3dgs(self.images, self.model, self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob(".3dgs-*")), [])

    def test_missing_ply_prevents_publishing_output(self):
        self.write_export = False
        with self.assertRaises(training.TrainingError):
            training.train_3dgs(self.images, self.model, self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob(".3dgs-*")), [])

    def test_failed_export_prevents_publishing_output(self):
        def fail_export(command, stage):
            if stage == "PLY 내보내기":
                raise training.TrainingError("내보내기 실패")
            self.simulate_nerfstudio(command, stage)

        self.runner.side_effect = fail_export
        with self.assertRaises(training.TrainingError):
            training.train_3dgs(self.images, self.model, self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob(".3dgs-*")), [])

    def test_invalid_input_prevents_training(self):
        with self.assertRaises(training.TrainingError):
            training.train_3dgs(
                self.images,
                self.model,
                self.output,
                max_iterations=0,
            )
        (self.model / "images.bin").unlink()
        with self.assertRaises(training.TrainingError):
            training.train_3dgs(self.images, self.model, self.output)
        self.runner.assert_not_called()

    def test_ply_validation_rejects_truncated_data_and_missing_properties(self):
        ply = self.root / "room.ply"
        self.write_ply(ply)
        training._validate_gaussian_ply(ply)

        ply.write_bytes(ply.read_bytes()[:-1])
        with self.assertRaises(training.TrainingError):
            training._validate_gaussian_ply(ply)

        self.write_ply(ply, omit_property="opacity")
        with self.assertRaises(training.TrainingError):
            training._validate_gaussian_ply(ply)

        self.write_ply(ply, vertices=0)
        with self.assertRaises(training.TrainingError):
            training._validate_gaussian_ply(ply)


class TrainingCommandTests(unittest.TestCase):
    def test_command_failure_includes_stage_exit_code_and_log(self):
        def fail(command, **options):
            options["stdout"].write("CUDA 학습 실패\n".encode("utf-8"))
            raise subprocess.CalledProcessError(1, command)

        with patch.object(training.subprocess, "run", side_effect=fail):
            with self.assertRaises(training.TrainingError) as caught:
                training._run_nerfstudio(["ns-train", "splatfacto"], "학습")
        self.assertIn("학습", str(caught.exception))
        self.assertIn("종료 코드 1", str(caught.exception))
        self.assertIn("CUDA 학습 실패", str(caught.exception))
