"""One-shot transfer commands; the reconstruction process is run separately."""

import argparse
import os
from pathlib import Path
import sys
from tempfile import NamedTemporaryFile
from uuid import UUID

from .transfers import TransferError, Transfers


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
    if args.action == "download":
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
        return
    if not lease:
        raise TransferError("Run download for this job first")
    if args.action == "upload":
        with args.ply.open("rb") as file:
            if args.ply.suffix.lower() != ".ply" or file.readline(8).strip() != b"ply":
                raise TransferError("The result must be a PLY file")
        grant = transfers.command(job_id, "result-upload", {
            "leaseId": lease, "sizeBytes": args.ply.stat().st_size,
        })
        transfers.upload(grant, args.ply)
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
        "upload": "Upload a local PLY and report completion",
        "complete": "Retry the completion report after an upload succeeded",
    }.items():
        command = commands.add_parser(action, help=help_text)
        command.add_argument("job_id", type=UUID)
        command.add_argument("--work-dir", required=True, type=Path)
        if action == "upload":
            command.add_argument("ply", type=Path)
    args = parser.parse_args()
    try:
        run(args, Transfers(os.getenv("BANGPICK_API_URL", ""), os.getenv("BANGPICK_MEDIA_WORKER_TOKEN", "")))
    except TransferError as error:
        parser.exit(1, f"{error}\n")
    except (ValueError, TypeError, AttributeError, KeyError):
        parser.exit(1, "Invalid local lease or backend response\n")
    except (OSError, EOFError):
        parser.exit(1, "File access or transfer failed; check the work directory and connection\n")


if __name__ == "__main__":
    main()
