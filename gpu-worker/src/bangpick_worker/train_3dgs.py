"""3DGS로 학습 후 PLY로 내보냄"""

from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory, TemporaryFile


class TrainingError(Exception):
    """3DGS 학습 또는 PLY 내보내기 실패."""

# Nerfstudio 명령 실행과 로그 처리
def _run_nerfstudio(command: list[str], stage: str) -> None:
    with TemporaryFile() as log:
        try:
            subprocess.run(command, check=True, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)

        except FileNotFoundError:
            raise TrainingError(f"Nerfstudio {stage} 명령을 실행할 수 없습니다") from None

        except subprocess.CalledProcessError as error:
            size = log.tell()
            log.seek(max(0, size - 8192))
            lines = log.read().decode("utf-8", errors="replace").strip().splitlines()
            detail = "\n".join(lines[-15:])
            raise TrainingError(f"Nerfstudio {stage} 실패 (종료 코드 {error.returncode})\n{detail}") from None

# 내보낸 PLY가 가우시안 속성을 갖고 있는지 확인
def _validate_gaussian_ply(path: Path) -> None:
    """Nerfstudio가 내보낸 PLY의 가우시안 속성과 데이터 크기를 확인"""
    if not path.is_file():
        raise TrainingError("Nerfstudio가 PLY 파일을 생성하지 않았습니다")

    with path.open("rb") as file:
        header, end_marker, _ = file.read(16_384).partition(b"end_header\n")
    lines = header.splitlines()

    if not end_marker or lines[:2] != [b"ply", b"format binary_little_endian 1.0"]:
        raise TrainingError("PLY 헤더가 올바르지 않습니다")

    vertex_count = next((line.split()[-1] for line in lines if line.startswith(b"element vertex ")), b"")
    properties = {line.removeprefix(b"property float ") for line in lines if line.startswith(b"property float ")}
    required = {
        b"x", b"y", b"z", b"opacity", b"f_dc_0", b"f_dc_1", b"f_dc_2",
        b"scale_0", b"scale_1", b"scale_2", b"rot_0", b"rot_1", b"rot_2", b"rot_3",
    }
    if not vertex_count.isdigit() or int(vertex_count) == 0 or not required.issubset(properties):
        raise TrainingError("PLY에 필요한 가우시안 데이터가 없습니다")

    # Nerfstudio가 가우시안 속성값을 float32로 저장해기 때문에, flaot32로 저장
    expected_size = len(header) + len(end_marker) + int(vertex_count) * len(properties) * 4
    if path.stat().st_size != expected_size:
        raise TrainingError("PLY 데이터 크기가 헤더에 기록된 가우시안 수와 맞지 않습니다")

# 학습 시작 전 검사
def train_3dgs(
    images_dir: Path,
    colmap_model_dir: Path,
    output_dir: Path,
    *,
    max_iterations: int = 20_000,
) -> Path:
    """선택한 COLMAP 모델로 Splatfacto를 학습하고 room.ply 경로를 반환"""
    if max_iterations <= 0:
        raise TrainingError("학습 스텝은 1 이상이어야 합니다")
    
    if not images_dir.is_dir() or not any(path.is_file() for path in images_dir.glob("*.jpg")):
        raise TrainingError("학습할 JPG가 있는 폴더를 찾을 수 없습니다")
    
    for filename in ("cameras.bin", "images.bin", "points3D.bin"):
        if not (colmap_model_dir / filename).is_file():
            raise TrainingError(f"COLMAP 모델에 {filename} 파일이 없습니다")
        
    if output_dir.exists():
        raise TrainingError("3DGS 출력 폴더가 이미 존재합니다")

    trainer = shutil.which("ns-train")
    exporter = shutil.which("ns-export")

    if trainer is None or exporter is None:
        raise TrainingError("PATH에서 ns-train과 ns-export를 찾을 수 있어야 합니다")

    output_dir.parent.mkdir(parents=True, exist_ok=True)
    images_dir = images_dir.resolve()
    colmap_model_dir = colmap_model_dir.resolve()

    # 학습 설정과 체크포인트는 내보내기까지 사용하고, 검증한 PLY만 최종 위치에 옮김.
    with TemporaryDirectory(prefix=".3dgs-", dir=output_dir.parent) as temporary:
        work_dir = Path(temporary).resolve()
        runs_dir = work_dir / "runs"
        run_dir = runs_dir / "scene" / "splatfacto" / "train"
        export_dir = work_dir / "export"

        _run_nerfstudio(
            [
                trainer, "splatfacto",
                "--data", str(images_dir.parent),
                "--output-dir", str(runs_dir),
                "--experiment-name", "scene",
                "--timestamp", "train",
                "--max-num-iterations", str(max_iterations),
                "--vis", "tensorboard",
                "colmap",
                "--images-path", str(images_dir),
                "--colmap-path", str(colmap_model_dir),
                "--downscale-factor", "1",
            ],
            "학습",
        )

        config = run_dir / "config.yml"
        if not config.is_file() or not any((run_dir / "nerfstudio_models").glob("step-*.ckpt")):
            raise TrainingError("이번 학습의 설정 파일이나 체크포인트가 없습니다")
        _run_nerfstudio(
            [
                exporter, "gaussian-splat",
                "--load-config", str(config),
                "--output-dir", str(export_dir),
                "--output-filename", "room.ply",
            ],
            "PLY 내보내기",
        )
        _validate_gaussian_ply(export_dir / "room.ply")
        export_dir.rename(output_dir)

    return output_dir / "room.ply"
