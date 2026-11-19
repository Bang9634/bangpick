"""One-shot commands for source transfer, frame extraction, and PLY upload."""

import argparse
import os
from pathlib import Path
from tempfile import NamedTemporaryFile
from uuid import UUID

from .transfers import TransferError, Transfers
from .preprocess import PreprocessError, extract_frames
from .colmap_poses import ColmapError, estimate_camera_poses
from .train_3dgs import TrainingError, train_3dgs
# 영상 전처리 -> COLMAP 카메라 위치 추정 -> 3DGS 학습 -> PLY 업로드 과정 추가

def save_lease(path: Path, lease: str) -> None:
    with NamedTemporaryFile(mode="w", encoding="ascii", dir=path.parent, delete=False) as file:
        temporary = Path(file.name)
        file.write(lease)
    try:
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def run(args: argparse.Namespace, transfers: Transfers) -> None:
    job_id = str(args.job_id)
    directory = args.work_dir / job_id
    lease_file = directory / "lease"
    lease = str(UUID(lease_file.read_text(encoding="ascii").strip())) if lease_file.exists() else None

    # reconstruct는 학습끝난 ply 경로를 넣음
    # upload는 사용자가 입력한 ply 경로를 사용함
    ply_path = args.ply if args.action == "upload" else None
    if args.action in {"download", "preprocess", "reconstruct"}:
        images = directory / "images"
        
        claim = transfers.command(job_id, "claim", {"leaseId": lease})
        lease = str(UUID(claim["leaseId"]))
        extension = claim["extension"]

        if extension not in {".mp4", ".mov", ".webm"}:
            raise TransferError("Backend returned an unsupported video extension")
        
        directory.mkdir(parents=True, exist_ok=True)
        # 백엔드는 이미 점유를 부여했다. 다운로드가 실패해도 같은 점유로 재시도할 수 있게 먼저 저장한다.
        save_lease(lease_file, lease)
        target = directory / ("source" + extension)
        transfers.download(claim["transfer"], target)
        print(f"Source saved to {target}")

        # download명령 시 원본영상 받고 종료
        if args.action == "download":
            return

        # 기존 JPG 재사용, 없으면 다시 추출함
        if images.exists():
            if not images.is_dir():
                raise PreprocessError(f"JPG 저장 폴더와 같은 이름의 파일이 이미 있습니다: {images}")

            count = sum(
                1 for frame in images.glob("frame_*.jpg") if frame.is_file() and frame.stat().st_size > 0
            )

            if count == 0:
                raise PreprocessError(f"재사용할 JPG 프레임이 없습니다: {images}")

            print((f"Reusing {count} frames from {images}"))

        else:
            count = extract_frames(target, images)
            print(f"Extracted {count} frames to {images}")


        if args.action == "preprocess":
            return

        model_dir = estimate_camera_poses(images, directory / "colmap", matching = args.matching)
        ply_path = train_3dgs(images, model_dir, directory / "3dgs")
     
    if not lease:
        raise TransferError("Run download for this job first")

    # upload와 reconstruct 명령 모두 ply_path를 사용함
    if ply_path is not None:
        with ply_path.open("rb") as file:
            if ply_path.suffix.lower() != ".ply" or file.readline(8).strip() != b"ply":
                raise TransferError("The result must be a PLY file")
            
        # 백엔드에 결과 크기와 점유ID를 보내 OCI 업로드 URL을 발급
        grant = transfers.command(job_id, "result-upload", {
            "leaseId": lease, "sizeBytes": ply_path.stat().st_size,
        })
        transfers.upload(grant, ply_path)

    # 파일 업로드 후 완료 보고가 실패하면 complete 명령으로 이 요청만 재시도한다.
    completed = transfers.command(job_id, "result-complete", {"leaseId": lease})
    if completed.get("status") != "RESULT_READY":
        raise TransferError("The backend did not confirm the result")
    print(f"Result recorded for job {job_id}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    for action, help_text in {
        "download": "Claim the job and download its source video",
        "preprocess": "Extract JPEG frames from the source video",
        "reconstruct": "Extract frames, estimate camera poses, train a 3DGS model, and upload the PLY file",
        "upload": "Upload a local PLY and report completion",
        "complete": "Retry the completion report after an upload succeeded",
    }.items():
        command = commands.add_parser(action, help=help_text)
        command.add_argument("job_id", type=UUID)
        command.add_argument("--work-dir", required=True, type=Path)

        if action == "reconstruct":
            command.add_argument(
                "--matching",
                choices=("sequential", "exhaustive"),
                default="sequential",
            )

        if action == "upload":
            command.add_argument("ply", type=Path)

    args = parser.parse_args()

    try:
        run(args, Transfers(os.getenv("BANGPICK_API_URL", ""), os.getenv("BANGPICK_MEDIA_WORKER_TOKEN", "")))
    except (TransferError, PreprocessError, ColmapError, TrainingError) as error:
        parser.exit(1, f"{error}\n")
    except (ValueError, TypeError, AttributeError, KeyError):
        parser.exit(1, "Invalid local lease or backend response\n")
    except (OSError, EOFError):
        parser.exit(1, "File access or transfer failed; check the work directory and connection\n")


if __name__ == "__main__":
    main()
