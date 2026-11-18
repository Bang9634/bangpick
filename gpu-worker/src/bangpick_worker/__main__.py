"""Transfer one job or supervise a local processing command with heartbeats."""

import argparse
import os
from pathlib import Path
import sys
from tempfile import NamedTemporaryFile
from uuid import UUID

from .transfers import PermanentTransferError, TransferError, Transfers
from .supervisor import renew_lease, supervise


def save_lease(path: Path, lease: str) -> None:
    with NamedTemporaryFile(mode="w", encoding="ascii", dir=path.parent, delete=False) as file:
        temporary = Path(file.name)
        file.write(lease)
    try:
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def report_input_failure(transfers: Transfers, job_id: str, lease: str, retryable: bool) -> None:
    try:
        result = transfers.command(job_id, "input-failed", {"leaseId": lease, "retryable": retryable})
    except TransferError:
        raise TransferError("Input failed and the backend did not confirm the failure; keep the lease file") from None
    status = result.get("status")
    if status not in {"QUEUED", "FAILED"}:
        raise TransferError("Backend returned an invalid failure status; keep the lease file")
    print(f"Job {job_id} is {status} after input failure")


def run(args: argparse.Namespace, transfers: Transfers) -> None:
    if args.action == "download-next":
        args.work_dir.mkdir(parents=True, exist_ok=True)
        next_job = transfers.claim_next()
        if next_job is None:
            print("No queued job")
            return
        job_id = str(UUID(next_job["jobId"]))
        lease = str(UUID(next_job["leaseId"]))
        directory = args.work_dir / job_id
        directory.mkdir(parents=True, exist_ok=True)
        lease_file = directory / "lease"
        # 점유가 이미 시작됐으므로 후속 API나 다운로드가 실패해도 ID를 잃지 않도록 먼저 기록한다.
        save_lease(lease_file, lease)
        print(f"Claimed job {job_id}")
    else:
        job_id = str(args.job_id)
        directory = args.work_dir / job_id
        lease_file = directory / "lease"
        lease = str(UUID(lease_file.read_text(encoding="ascii").strip())) if lease_file.exists() else None
    if args.action in {"download", "download-next"}:
        try:
            claim = transfers.command(job_id, "claim", {"leaseId": lease})
            claimed_lease = str(UUID(claim["leaseId"]))
            if args.action == "download-next" and claimed_lease != lease:
                raise PermanentTransferError("Backend returned a different lease for the queued job")
            lease = claimed_lease
            extension = claim["extension"]
            if extension not in {".mp4", ".mov", ".webm"}:
                raise PermanentTransferError("Backend returned an unsupported video extension")
            directory.mkdir(parents=True, exist_ok=True)
            # 백엔드는 이미 점유를 부여했다. 다운로드가 실패해도 같은 점유로 재시도할 수 있게 먼저 저장한다.
            save_lease(lease_file, lease)
            target = directory / ("source" + extension)
            transfers.download(claim["transfer"], target)
        except (TransferError, OSError, ValueError, TypeError, AttributeError, KeyError) as error:
            if args.action == "download-next":
                retryable = isinstance(error, (TransferError, OSError)) and not isinstance(error, PermanentTransferError)
                report_input_failure(transfers, job_id, lease, retryable)
            raise
        print(f"Source saved to {target}")
        return
    if not lease:
        raise TransferError("Run download for this job first")
    if args.action == "heartbeat":
        expires_at = renew_lease(transfers, job_id, lease)
        print(f"Lease for job {job_id} is valid until {expires_at}")
        return
    if args.action == "run-process":
        result = supervise(job_id, lease, args.work_dir, args.processor, transfers)
        if result != 0:
            raise TransferError(f"Processing command exited with code {result}; the job remains PROCESSING")
        print(f"Processing command finished for job {job_id}; upload the result separately")
        return
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
        "download-next": "Claim the next queued job and download its source video",
        "download": "Claim the job and download its source video",
        "heartbeat": "Renew the current lease for a job once",
        "run-process": "Run one local command with automatic heartbeats",
        "upload": "Upload a local PLY and report completion",
        "complete": "Retry the completion report after an upload succeeded",
    }.items():
        command = commands.add_parser(action, help=help_text)
        if action != "download-next":
            command.add_argument("job_id", type=UUID)
        command.add_argument("--work-dir", required=True, type=Path)
        if action == "upload":
            command.add_argument("ply", type=Path)
    argv = sys.argv[1:]
    if argv[:1] == ["run-process"]:
        if "--" not in argv:
            if "-h" in argv or "--help" in argv:
                parser.parse_args(argv)
            parser.error("run-process requires -- before the processing command")
        separator = argv.index("--")
        args = parser.parse_args(argv[:separator])
        args.processor = argv[separator + 1:]
        if not args.processor:
            parser.error("run-process requires a processing command after --")
    else:
        args = parser.parse_args(argv)
    try:
        run(args, Transfers(os.getenv("BANGPICK_API_URL", ""), os.getenv("BANGPICK_MEDIA_WORKER_TOKEN", "")))
    except TransferError as error:
        parser.exit(1, f"{error}\n")
    except (ValueError, TypeError, AttributeError, KeyError):
        parser.exit(1, "Invalid local lease or backend response\n")
    except (OSError, EOFError):
        parser.exit(1, "File access or transfer failed; check the work directory and connection\n")
    except KeyboardInterrupt:
        parser.exit(130, "Processing interrupted\n")


if __name__ == "__main__":
    main()
