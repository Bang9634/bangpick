"""Transfer media and process queued jobs with a local command."""

import argparse
from enum import Enum
import os
import json
from pathlib import Path
import signal
import sys
from tempfile import NamedTemporaryFile
import time
from uuid import UUID

from .transfers import CHUNK_SIZE, PermanentTransferError, TransferError, Transfers
from .supervisor import ProcessStartError, local_processing_slot, renew_lease, supervise, supervise_in_slot

from .preprocess import extract_frames
from .colmap_poses import InsufficientRegisteredImagesError ,estimate_camera_poses
from .train_3dgs import train_3dgs

class ProcessNextResult(Enum):
    NO_JOB = "no_job"
    COMPLETED = "completed"


class ReportedJobFailure(TransferError):
    """The backend confirmed QUEUED or FAILED for this attempt.

    This does not prove that a processor's detached descendants have stopped.
    """

    def __init__(self, job_id: str, status: str, message: str):
        self.job_id = job_id
        self.status = status
        super().__init__(message)


class TerminationRequested(KeyboardInterrupt):
    """The long-running loop received SIGTERM."""


def save_lease(path: Path, lease: str) -> None:
    with NamedTemporaryFile(mode="w", encoding="ascii", dir=path.parent, delete=False) as file:
        temporary = Path(file.name)
        file.write(lease)
    try:
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def report_input_failure(transfers: Transfers, job_id: str, lease: str, retryable: bool) -> str:
    return report_failure(transfers, job_id, lease, "input-failed", retryable)


def report_failure(transfers: Transfers, job_id: str, lease: str, action: str, retryable: bool) -> str:
    try:
        result = transfers.command(job_id, action, {"leaseId": lease, "retryable": retryable})
    except TransferError:
        raise TransferError("The backend did not confirm the failure; keep the lease file") from None
    status = result.get("status")
    if status not in {"QUEUED", "FAILED"}:
        raise TransferError("Backend returned an invalid failure status; keep the lease file")
    stage = "input" if action == "input-failed" else "processing"
    print(f"Job {job_id} is {status} after {stage} failure")
    return status


def download_assigned(job_id: str, lease: str | None, directory: Path, transfers: Transfers,
                      report_failure_on_error: bool) -> tuple[str, Path]:
    lease_file = directory / "lease"
    try:
        claim = transfers.command(job_id, "claim", {"leaseId": lease})
        claimed_lease = str(UUID(claim["leaseId"]))
        if report_failure_on_error and claimed_lease != lease:
            raise PermanentTransferError("Backend returned a different lease for the queued job")
        lease = claimed_lease
        extension = claim["extension"]
        if extension not in {".mp4", ".mov", ".webm"}:
            raise PermanentTransferError("Backend returned an unsupported video extension")
        directory.mkdir(parents=True, exist_ok=True)
        # 수동 다운로드는 저장한 점유 ID로 재시도한다. 자동 점유는 실패 보고가
        # 확인되지 않았을 때 이 ID로 백엔드 상태를 확인한다.
        save_lease(lease_file, lease)
        target = directory / ("source" + extension)
        transfers.download(claim["transfer"], target)
    except (TransferError, OSError, ValueError, TypeError, AttributeError, KeyError) as error:
        if report_failure_on_error:
            retryable = isinstance(error, (TransferError, OSError)) and not isinstance(error, PermanentTransferError)
            status = report_input_failure(transfers, job_id, lease, retryable)
            message = str(error) if isinstance(error, TransferError) else "Input file access or transfer failed"
            raise ReportedJobFailure(job_id, status, message) from error
        raise
    print(f"Source saved to {target}")
    return lease, target


def download_next_job(work_dir: Path, transfers: Transfers) -> tuple[str, str, Path, str] | None:
    work_dir.mkdir(parents=True, exist_ok=True)
    next_job = transfers.claim_next()
    if next_job is None:
        print("No queued job")
        return None
    job_id = str(UUID(next_job["jobId"]))
    lease = str(UUID(next_job["leaseId"]))
    directory = work_dir / job_id
    try:
        directory.mkdir(parents=True, exist_ok=True)
        # 점유 응답 직후 기록한다. 기록할 수 없다면 메모리의 점유 ID로 실패를 보고한다.
        save_lease(directory / "lease", lease)
    except OSError as error:
        try:
            status = report_input_failure(transfers, job_id, lease, True)
        except TransferError:
            raise TransferError(f"Could not save the lease for job {job_id}; input failure was not confirmed") from None
        raise ReportedJobFailure(job_id, status, f"Could not save the lease for job {job_id}") from error
    print(f"Claimed job {job_id}")
    lease, source = download_assigned(job_id, lease, directory, transfers, True)
    matching = next_job.get("matching", "sequential")
    return job_id, lease, source, matching


def validate_ply(ply: Path) -> int:
    """존재·확장자·크기·첫 줄만 검사한다. Gaussian 속성과 웹 호환성은 검증하지 않는다."""
    if ply.suffix.lower() != ".ply" or not ply.is_file():
        raise TransferError("The result must be a PLY file")
    size = ply.stat().st_size
    if not 0 < size <= 256 * CHUNK_SIZE:
        raise TransferError("The PLY is empty or exceeds the size limit")
    with ply.open("rb") as file:
        if file.readline(8).strip() != b"ply":
            raise TransferError("The result must be a PLY file")
    return size


def upload_ply(job_id: str, lease: str, ply: Path, transfers: Transfers) -> None:
    grant = transfers.command(job_id, "result-upload", {
        "leaseId": lease, "sizeBytes": validate_ply(ply),
    })
    transfers.upload(grant, ply)


def complete_result(job_id: str, lease: str, transfers: Transfers) -> None:
    completed = transfers.command(job_id, "result-complete", {"leaseId": lease})
    if completed.get("status") != "RESULT_READY":
        raise TransferError("The backend did not confirm the result")
    print(f"Result recorded for job {job_id}")


def build_3d_scene(input_video: Path, output_ply: Path, *, matching: str = "sequential") -> Path:
    """영상에서 프레임을 추출하고 COLMAP로 촬영 위치와 방향을 추정한 뒤 3DGS로 학습하여 PLY 파일경로를 반환"""
    if output_ply.exists():
        raise FileExistsError(f"PLY 출력 파일이 이미 존재합니다: {output_ply}")
    
    attempt_dir = output_ply.parent
    images_dir = attempt_dir / "images"
    colmap_dir = attempt_dir / "colmap"
    training_dir = attempt_dir / "3dgs"

    extract_frames(input_video, images_dir)
    model_dir = estimate_camera_poses(images_dir, colmap_dir, matching=matching)
    generated_ply = train_3dgs(images_dir, model_dir, training_dir)
    return generated_ply.rename(output_ply)

def read_registration_failure(path: Path) -> InsufficientRegisteredImagesError | None:
    """이번 시도의 실패 파일에서 COLMAP 등록기준 미달 정보를 읽는다"""
    try:
        failure = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None

    if not isinstance(failure, dict):
        return None
    if failure.get("code") != "INSUFFICIENT_REGISTERED_IMAGES":
        return None

    matching = failure.get("matching")
    registered = failure.get("registeredImages")
    total = failure.get("totalImages")

    if matching not in ("sequential", "exhaustive"):
        return None
    if type(registered) is not int or type(total) is not int:
        return None
    if total <= 0 or not 0 <= registered <= total:
        return None

    return InsufficientRegisteredImagesError(
        registered_images=registered,
        total_images=total,
        matching=matching,
    )

def process_next(args: argparse.Namespace, transfers: Transfers) -> ProcessNextResult:
    """잠금을 점유 전부터 완료 보고까지 유지하며 작업 한 건을 처리한다.

    점유 ID를 받은 뒤 입력 저장·다운로드 실패는 input-failed로, 처리 명령의
    시작 실패·비정상 종료·PLY 검사 실패는 processing-failed로 보고한다.
    대기 작업 없음·완료는 반환하고, 실패 보고가 확인되면 ReportedJobFailure를 낸다.
    heartbeat나 결과 업로드·완료 확인이 불확실하면 자동 재대기시키지 않는다.
    """
    if not args.processor:
        raise TransferError("Specify a processing command before claiming a job")
    with local_processing_slot(args.work_dir):
        claimed = download_next_job(args.work_dir, transfers)
        if claimed is None:
            return ProcessNextResult.NO_JOB
        job_id, lease, source, matching = claimed
        output = args.work_dir / job_id / "attempts" / lease / "room.ply"
        try:
            if matching not in ("sequential", "exhaustive"):
                raise ProcessStartError("COLMAP matching must be sequential or exhaustive")

            command = [matching if argument == "{matching}" else argument
                       for argument in args.processor]

            result = supervise_in_slot(job_id, lease, args.work_dir, command, transfers,
                                       input_video=source, output_ply=output)
            
        except ProcessStartError as error:
            status = report_failure(transfers, job_id, lease, "processing-failed", args.retryable)
            raise ReportedJobFailure(job_id, status, str(error)) from error
        if result != 0:
            failure = read_registration_failure(output.parent / "failure.json")

            if failure is None:
                message = f"Processing command exited with code {result}"
                retryable = args.retryable
            else:
                message = f"COLMAP {failure.matching}: {failure}"
                retryable = False

            status = report_failure(
                transfers, job_id, lease, "processing-failed", retryable
            )
            raise ReportedJobFailure(job_id, status, message)
        
        try:
            validate_ply(output)
        except (TransferError, OSError) as error:
            status = report_failure(transfers, job_id, lease, "processing-failed", args.retryable)
            message = str(error) if isinstance(error, TransferError) else "Could not read the PLY result"
            raise ReportedJobFailure(job_id, status, message) from error
        try:
            upload_ply(job_id, lease, output, transfers)
        except (TransferError, OSError):
            raise TransferError(f"Result upload for job {job_id} was not confirmed; retry upload using {output}") from None
        try:
            complete_result(job_id, lease, transfers)
        except TransferError:
            raise TransferError(f"Completion for job {job_id} was not confirmed; retry complete") from None
        return ProcessNextResult.COMPLETED


def work_loop(args: argparse.Namespace, transfers: Transfers) -> None:
    """Poll for jobs; stop if the current attempt's backend state is uncertain.

    A reported failure permits another claim only when the processor command
    has also stopped its own GPU descendants before exiting.
    """
    def stop_on_sigterm(_signum, _frame):
        raise TerminationRequested

    previous_handler = signal.signal(signal.SIGTERM, stop_on_sigterm)
    try:
        while True:
            try:
                result = process_next(args, transfers)
            except ReportedJobFailure as error:
                print(f"Job {error.job_id} is {error.status}: {error}", file=sys.stderr)
                continue
            if result == ProcessNextResult.NO_JOB:
                time.sleep(5)
            elif result != ProcessNextResult.COMPLETED:
                raise TransferError("Processing returned an unknown result; stopping the worker")
    finally:
        signal.signal(signal.SIGTERM, previous_handler)


def run(args: argparse.Namespace, transfers: Transfers) -> None:
    if args.action == "process-next":
        process_next(args, transfers)
        return
    if args.action == "work-loop":
        work_loop(args, transfers)
        return
    if args.action == "download-next":
        with local_processing_slot(args.work_dir):
            download_next_job(args.work_dir, transfers)
        return

    job_id = str(args.job_id)
    directory = args.work_dir / job_id
    lease_file = directory / "lease"
    # A manual retry can explicitly start a new attempt even when an old lease file remains.
    new_lease = args.action == "download" and getattr(args, "new_lease", False)
    lease = (str(UUID(lease_file.read_text(encoding="ascii").strip()))
             if lease_file.exists() and not new_lease else None)
    if args.action == "download":
        with local_processing_slot(args.work_dir):
            download_assigned(job_id, lease, directory, transfers, False)
        return
    if not lease:
        raise TransferError("Run download for this job first")
    if args.action == "heartbeat":
        expires_at = renew_lease(transfers, job_id, lease)
        print(f"Lease for job {job_id} is valid until {expires_at}")
        return
    if args.action == "run-process":
        try:
            result = supervise(job_id, lease, args.work_dir, args.processor, transfers)
        except ProcessStartError:
            report_failure(transfers, job_id, lease, "processing-failed", args.retryable)
            raise
        if result != 0:
            report_failure(transfers, job_id, lease, "processing-failed", args.retryable)
            raise TransferError(f"Processing command exited with code {result}")
        print(f"Processing command finished for job {job_id}; upload the result separately")
        return
    if args.action == "processing-failed":
        with local_processing_slot(args.work_dir):
            report_failure(transfers, job_id, lease, "processing-failed", args.retryable)
        return
    with local_processing_slot(args.work_dir):
        if args.action == "upload":
            upload_ply(job_id, lease, args.ply, transfers)
        # 파일 업로드 후 완료 보고가 실패하면 complete 명령으로 이 요청만 재시도한다.
        complete_result(job_id, lease, transfers)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    for action, help_text in {
        "download-next": "Claim the next queued job and download its source video",
        "process-next": "Claim, process and upload one queued job",
        "work-loop": "Continuously claim and process queued jobs",
        "download": "Claim the job and download its source video",
        "heartbeat": "Renew the current lease for a job once",
        "run-process": "Run one local command with automatic heartbeats",
        "processing-failed": "Report a stopped processing attempt without rerunning it",
        "upload": "Upload a local PLY and report completion",
        "complete": "Retry the completion report after an upload succeeded",
    }.items():
        command = commands.add_parser(action, help=help_text)
        if action not in {"download-next", "process-next", "work-loop"}:
            command.add_argument("job_id", type=UUID)
        command.add_argument("--work-dir", required=True, type=Path)
        if action == "upload":
            command.add_argument("ply", type=Path)
        if action == "download":
            command.add_argument("--new-lease", action="store_true",
                                 help="Claim a queued job again and replace the saved lease")
        if action in {"run-process", "process-next", "work-loop", "processing-failed"}:
            command.add_argument("--retryable", action="store_true",
                                 help="Retry a failed attempt, up to three total attempts")
        if action in {"run-process", "process-next", "work-loop"}:
            command.epilog = "Put -- before the local processing command."
    scene_command = commands.add_parser("build-3d-scene", 
                        help="Build a 3D scene from the input video and save the output PLY",)
    scene_command.add_argument("--matching", choices=["sequential", "exhaustive"], default="sequential",
                               help="COLMAP matching strategy (default: sequential)")    
    
    argv = sys.argv[1:]
    if argv[:1] in (["run-process"], ["process-next"], ["work-loop"]):
        if "--" not in argv:
            if "-h" in argv or "--help" in argv:
                parser.parse_args(argv)
            parser.error(f"{argv[0]} requires -- before the processing command")
        separator = argv.index("--")
        args = parser.parse_args(argv[:separator])
        args.processor = argv[separator + 1:]
        if not args.processor:
            parser.error(f"{argv[0]} requires a processing command after --")
    else:
        args = parser.parse_args(argv)
    try:
        if args.action == "build-3d-scene":
            input_video = os.getenv("BANGPICK_INPUT_VIDEO")
            output_ply = os.getenv("BANGPICK_OUTPUT_PLY")

            if not input_video or not output_ply:
                parser.error("BANGPICK_INPUT_VIDEO and BANGPICK_OUTPUT_PLY environment variables must be set for build-3d-scene")
            
            try:
                build_3d_scene(Path(input_video), Path(output_ply), matching=args.matching)

            except InsufficientRegisteredImagesError as error:
                failure = {
                    "code": "INSUFFICIENT_REGISTERED_IMAGES",
                    "matching": error.matching,
                    "registeredImages": error.registered_images,
                    "totalImages": error.total_images,
                }

                failure_path = Path(output_ply).parent / "failure.json"
                temporary_path = failure_path.with_suffix(".json.tmp")
                temporary_path.write_text(json.dumps(failure), encoding="utf-8")
                temporary_path.replace(failure_path)

                parser.exit(1, f"{error}\n")
            return
        
        run(args, Transfers(os.getenv("BANGPICK_API_URL", ""), os.getenv("BANGPICK_MEDIA_WORKER_TOKEN", "")))
        
    except TerminationRequested:
        parser.exit(143, "Worker terminated\n")
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
