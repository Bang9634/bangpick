"""Run one local processing command while its backend lease remains active."""

from contextlib import contextmanager
from datetime import datetime
import os
from pathlib import Path
import subprocess

from .transfers import TransferError, Transfers

HEARTBEAT_INTERVAL_SECONDS = 60


class ProcessStartError(TransferError):
    """The supervised command never started."""


def renew_lease(transfers: Transfers, job_id: str, lease: str) -> str:
    renewed = transfers.command(job_id, "heartbeat", {"leaseId": lease})
    expires_at = renewed.get("leaseExpiresAt")
    if not isinstance(expires_at, str):
        raise TransferError("Backend returned an invalid lease expiry")
    try:
        parsed = datetime.fromisoformat(expires_at.replace("Z", "+00:00"))
    except ValueError:
        raise TransferError("Backend returned an invalid lease expiry") from None
    if parsed.tzinfo is None:
        raise TransferError("Backend returned an invalid lease expiry")
    return expires_at


@contextmanager
def local_processing_slot(work_dir: Path):
    """Allow one supervised processing command per work directory on this host."""
    work_dir.mkdir(parents=True, exist_ok=True)
    with (work_dir / ".processing.lock").open("a+b") as lock_file:
        lock_file.seek(0, os.SEEK_END)
        if lock_file.tell() == 0:
            lock_file.write(b"0")
            lock_file.flush()
        lock_file.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock_file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise TransferError("Another job is already processing in this work directory") from None
        try:
            yield
        finally:
            lock_file.seek(0)
            if os.name == "nt":
                msvcrt.locking(lock_file.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)


def _stop_process(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def _job_directory(job_id: str, work_dir: Path, command: list[str], heartbeat_interval: float) -> Path:
    if not command or heartbeat_interval <= 0:
        raise TransferError("Specify a processing command and a positive heartbeat interval")
    job_dir = work_dir / job_id
    if not job_dir.is_dir() or not any(
        (job_dir / f"source{extension}").is_file()
        and (job_dir / f"source{extension}").stat().st_size > 0
        for extension in (".mp4", ".mov", ".webm")
    ):
        raise TransferError("Download the job before running a processing command")
    return job_dir


def supervise(job_id: str, lease: str, work_dir: Path, command: list[str], transfers: Transfers,
              heartbeat_interval: float = HEARTBEAT_INTERVAL_SECONDS) -> int:
    _job_directory(job_id, work_dir, command, heartbeat_interval)
    with local_processing_slot(work_dir):
        return supervise_in_slot(job_id, lease, work_dir, command, transfers, heartbeat_interval)


def supervise_in_slot(job_id: str, lease: str, work_dir: Path, command: list[str], transfers: Transfers,
                      heartbeat_interval: float = HEARTBEAT_INTERVAL_SECONDS, *,
                      input_video: Path | None = None, output_ply: Path | None = None) -> int:
    """Run a command while the caller holds local_processing_slot(work_dir)."""
    job_dir = _job_directory(job_id, work_dir, command, heartbeat_interval)
    if (input_video is None) != (output_ply is None):
        raise TransferError("Specify both processing input and output paths")
    if output_ply is not None:
        output_ply.parent.mkdir(parents=True, exist_ok=True)
        if output_ply.exists():
            raise TransferError("The output path already contains a result for this attempt")

    # 시작 전 갱신이 거부되면 처리 명령을 실행하지 않는다.
    renew_lease(transfers, job_id, lease)
    environment = os.environ.copy()
    environment.pop("BANGPICK_MEDIA_WORKER_TOKEN", None)
    if input_video is not None:
        environment["BANGPICK_INPUT_VIDEO"] = str(input_video.resolve())
        environment["BANGPICK_OUTPUT_PLY"] = str(output_ply.resolve())
    try:
        process = subprocess.Popen(command, cwd=job_dir, env=environment)
    except OSError:
        raise ProcessStartError("Could not start the processing command") from None
    try:
        while True:
            try:
                return process.wait(timeout=heartbeat_interval)
            except subprocess.TimeoutExpired:
                if process.poll() is not None:
                    return process.returncode
                renew_lease(transfers, job_id, lease)
    except BaseException:
        _stop_process(process)
        raise
