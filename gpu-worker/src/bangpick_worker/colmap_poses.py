"""JPG에서 카메라 위치·방향과 희소 3D 점을 추정하는 단계(COLMAP)"""

import json
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from typing import Literal


class ColmapError(Exception):
    """COLMAP 실행 또는 결과 확인 실패."""


def _run_colmap(executable: str, command: str, *arguments: str) -> str:

    try:
        completed = subprocess.run(
            [executable, command, *arguments],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            encoding="utf-8",
            errors="replace",
        )

    except FileNotFoundError:
        raise ColmapError("PATH에서 COLMAP 실행 파일을 찾을 수 없습니다") from None
    
    except subprocess.CalledProcessError as error:
        lines = (error.stdout or "").strip().splitlines()
        detail = "\n".join([f"종료 코드 {error.returncode}", *lines[-5:]])
        raise ColmapError(f"COLMAP {command} 실행 실패\n{detail}") from None
    
    return completed.stdout


def _read_record_count(path: Path) -> int:
    # 추출한 COLMAP 특징점 파일(.bin)에서 8바이트씩 읽기
    if not path.is_file():
        raise ColmapError(f"COLMAP 모델 파일이 없습니다 ({path.parent.name}/{path.name})")
    
    with path.open("rb") as file:
        header = file.read(8)

    if len(header) < 8:
        raise ColmapError(f"COLMAP 모델 파일의 헤더가 잘렸습니다 ({path.parent.name}/{path.name})")
    
    return int.from_bytes(header, byteorder="little")


def estimate_camera_poses(
    images_dir: Path,
    output_dir: Path,
    *,
    matching: Literal["sequential", "exhaustive"] = "sequential",
    # 매칭 기본값을 순차매칭으로 해놨음. 더 나은 매칭 알고리즘 찾으면 변경 가능.

    single_camera: bool = True,
) -> Path:
    
    """JPG -> 각 사진을 찍은 위치와 방향을 추정, 장면의 3D 점을 계산함.
    결과가 여러 모델로 나뉘면, 촬영 위치와 방향을 알아낸 사진이 가장 많은 모델의 폴더 경로를 반환.
    기본값은 촬영 순서상 가까운 사진끼리 비교함(순차 매칭).
    이 방식으로 연결되는 사진이 적으면 matching="exhaustive"로 모든 사진 쌍을 비교할 수 있음.
    영상 중간에 렌즈나 줌 배율이 바뀌었다면 single_camera=False로 카메라 내부 설정의 공유를 해제할 수 있음.
    """

    if matching not in {"sequential", "exhaustive"}:
        raise ColmapError("순차 매칭과 전수 매칭 중 하나를 지정해야 합니다")
    
    if not images_dir.is_dir():
        raise ColmapError("프레임 이미지 폴더가 없습니다")
    
    image_names = sorted(path.name for path in images_dir.glob("*.jpg") if path.is_file())

    if len(image_names) < 2:
        raise ColmapError("카메라 추정에는 JPG 프레임이 두 장 이상 필요합니다")
    
    if output_dir.exists():
        raise ColmapError("COLMAP 출력 폴더가 이미 존재합니다")

    executable = shutil.which("colmap")

    if executable is None:
        raise ColmapError("PATH에서 COLMAP 실행 파일을 찾을 수 없습니다")

    # COLMAP 작업 폴더 준비
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    # 실패한 작업의 DB와 모델이 다음 실행에 섞이지 않도록 임시 폴더에서 처리함.
    with TemporaryDirectory(prefix=".colmap-", dir=output_dir.parent) as temporary:
        work_dir = Path(temporary).resolve()
        staged = work_dir / "result"
        sparse_dir = staged / "sparse"
        sparse_dir.mkdir(parents=True)
        database_path = str(staged / "database.db")
        image_path = str(images_dir.resolve())

        image_list = work_dir / "images.txt"
        image_list.write_text("\n".join(image_names) + "\n", encoding="utf-8")

        # COLMAP 실행 (JPG -> 특징점 추출 -> 매칭 -> 카메라 위치·방향 추정)
        # SIFT 특징점 정보 저장
        _run_colmap(
            executable, "feature_extractor",
            "--database_path", database_path,
            "--image_path", image_path,
            "--image_list_path", str(image_list),
            "--ImageReader.single_camera", "1" if single_camera else "0",
        )

        # 서로 다른 사진의 특징점끼리 매칭하는 단계
        matcher = f"{matching}_matcher"
        matcher_help = _run_colmap(executable, matcher, "--help")
        match_limit = "--FeatureMatching.max_num_matches"

        if match_limit not in matcher_help:
            match_limit = "--SiftMatching.max_num_matches"
        _run_colmap(executable, matcher, "--database_path", database_path, match_limit, "12000")
        # 특징점 매칭 상한이 32,768인데 내 GPU VRAM이 부족해서 12,000으로 낮춰놨음.
        _run_colmap(
            executable, "mapper",
            "--database_path", database_path,
            "--image_path", image_path,
            "--output_path", str(sparse_dir),
        )

        # COLMAP 모델 검사
        models = {}
        for model_dir in sorted(sparse_dir.iterdir()):
            if not model_dir.is_dir():
                continue

            cameras = _read_record_count(model_dir / "cameras.bin")
            registered_images = _read_record_count(model_dir / "images.bin")
            points = _read_record_count(model_dir / "points3D.bin")
            
            if not cameras or not 2 <= registered_images <= len(image_names) or not points:
                raise ColmapError(f"카메라 또는 3D 점이 유효하지 않은 COLMAP 모델입니다 ({model_dir.name})")
            
            models[model_dir.name] = {"registered_images": registered_images, "points3d": points}

        if not models:
            raise ColmapError("COLMAP이 희소 모델을 생성하지 못했습니다")

        # 모델이 연결할 수 있는 사진을 찾지 못하면, 모델 B로 분열
        # 모델중에서 가장 많은 프레임이 등록된 모델을 학습에 사용함.
        # 그래서 모델 분할이 적을수록 좋은데, 여기더 손봐야함.
        # 내 자취방 실험할 떄 2개로 분리되는 문제발생.
        # 결론: 모델이 분리되는 경우는 COLMAP이 사진을 연결하지 못한 경우
        selected = max(models, key=lambda name: models[name]["registered_images"])
        model_path = Path("sparse") / selected
        summary = {
            "input_images": len(image_names),
            "matching": matching,
            "model_count": len(models),
            "selected_model": model_path.as_posix(),
            "models": models,
        }
        (staged / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        staged.rename(output_dir)

    return output_dir / model_path
