"""COLMAP 응답을 재현해 실행 순서와 모델 선택, 실패 정리를 확인한다."""

import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from bangpick_worker import colmap_poses


class ColmapTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.images = self.root / "images"
        self.images.mkdir()
        for number in range(1, 7):
            (self.images / f"frame_{number:06d}.jpg").touch()
        self.output = self.root / "colmap"
        self.model_counts = {"0": 2, "1": 5}
        self.matcher_option = "--FeatureMatching.max_num_matches"

        executable = patch.object(
            colmap_poses.shutil,
            "which",
            return_value="colmap",
        )
        executable.start()
        self.addCleanup(executable.stop)
        runner = patch.object(
            colmap_poses,
            "_run_colmap",
            side_effect=self.simulate_colmap,
        )
        self.runner = runner.start()
        self.addCleanup(runner.stop)

    def simulate_colmap(self, executable, command, *arguments):
        """결과를 읽는 코드를 실행할 수 있도록 필요한 파일을 만든다."""
        if "--help" in arguments:
            return self.matcher_option
        if command == "feature_extractor":
            database = Path(arguments[arguments.index("--database_path") + 1])
            database.touch()
            image_list = Path(arguments[arguments.index("--image_list_path") + 1])
            self.assertEqual(
                image_list.read_text(encoding="utf-8").splitlines(),
                sorted(path.name for path in self.images.glob("*.jpg")),
            )
        if command == "mapper":
            sparse = Path(arguments[arguments.index("--output_path") + 1])
            # 현재 코드는 모델 파일의 개수 헤더를 읽으므로 헤더만 재현한다.
            for name, count in self.model_counts.items():
                model = sparse / name
                model.mkdir()
                for filename, records in {
                    "cameras.bin": 1,
                    "images.bin": count,
                    "points3D.bin": 10,
                }.items():
                    (model / filename).write_bytes(records.to_bytes(8, "little"))
        return ""

    def test_selects_largest_model_and_records_all_models(self):
        selected = colmap_poses.estimate_camera_poses(self.images, self.output)

        self.assertEqual(selected, self.output / "sparse" / "1")
        self.assertTrue((selected / "images.bin").is_file())
        summary = json.loads(
            (self.output / "summary.json").read_text(encoding="utf-8")
        )
        self.assertEqual(summary["input_images"], 6)
        self.assertEqual(summary["model_count"], 2)
        self.assertEqual(summary["selected_model"], "sparse/1")
        self.assertEqual(summary["models"]["0"]["registered_images"], 2)
        self.assertEqual(summary["models"]["1"]["registered_images"], 5)
        self.assertEqual(list(self.root.glob(".colmap-*")), [])

    def test_supports_matching_modes_and_version_specific_option_names(self):
        for mode in ("sequential", "exhaustive"):
            for option in (
                "--FeatureMatching.max_num_matches",
                "--SiftMatching.max_num_matches",
            ):
                with self.subTest(mode=mode, option=option):
                    self.matcher_option = option
                    self.runner.reset_mock()
                    output = self.root / f"{mode}-{option.split('.')[0]}"
                    colmap_poses.estimate_camera_poses(
                        self.images,
                        output,
                        matching=mode,
                        single_camera=False,
                    )
                    calls = self.runner.call_args_list
                    self.assertEqual(
                        [call.args[1] for call in calls],
                        [
                            "feature_extractor",
                            f"{mode}_matcher",
                            f"{mode}_matcher",
                            "mapper",
                        ],
                    )
                    self.assertEqual(
                        calls[0].args[-2:],
                        ("--ImageReader.single_camera", "0"),
                    )
                    self.assertEqual(calls[2].args[-2:], (option, "12000"))

    def test_missing_input_and_existing_output_prevent_execution(self):
        with self.assertRaises(colmap_poses.ColmapError):
            colmap_poses.estimate_camera_poses(self.root / "missing", self.output)
        self.output.mkdir()
        with self.assertRaises(colmap_poses.ColmapError):
            colmap_poses.estimate_camera_poses(self.images, self.output)
        self.runner.assert_not_called()

    def test_failure_removes_staged_results_and_allows_fresh_attempt(self):
        self.runner.side_effect = colmap_poses.ColmapError("매칭 실패")
        with self.assertRaises(colmap_poses.ColmapError):
            colmap_poses.estimate_camera_poses(self.images, self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob(".colmap-*")), [])

        self.runner.side_effect = self.simulate_colmap
        selected = colmap_poses.estimate_camera_poses(self.images, self.output)
        self.assertTrue(selected.is_dir())

    def test_rejects_missing_model_and_impossible_registration_count(self):
        for counts in ({}, {"0": 0}, {"0": 7}):
            with self.subTest(counts=counts):
                self.model_counts = counts
                with self.assertRaises(colmap_poses.ColmapError):
                    colmap_poses.estimate_camera_poses(self.images, self.output)
                self.assertFalse(self.output.exists())
                self.assertEqual(list(self.root.glob(".colmap-*")), [])


class ColmapCommandTests(unittest.TestCase):
    def test_command_failure_includes_stage_exit_code_and_log(self):
        error = subprocess.CalledProcessError(
            1,
            ["colmap", "mapper"],
            output="재구성 실패 로그",
        )
        with patch.object(colmap_poses.subprocess, "run", side_effect=error):
            with self.assertRaises(colmap_poses.ColmapError) as caught:
                colmap_poses._run_colmap("colmap", "mapper")
        self.assertIn("mapper", str(caught.exception))
        self.assertIn("종료 코드 1", str(caught.exception))
        self.assertIn("재구성 실패 로그", str(caught.exception))

    def test_record_count_reads_uint64_and_rejects_truncated_header(self):
        with TemporaryDirectory() as temporary:
            path = Path(temporary) / "images.bin"
            path.write_bytes((199).to_bytes(8, "little"))
            self.assertEqual(colmap_poses._read_record_count(path), 199)
            path.write_bytes(b"short")
            with self.assertRaises(colmap_poses.ColmapError):
                colmap_poses._read_record_count(path)
