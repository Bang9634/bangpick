"""입력 영상을 프레임으로 추출하는 단계"""
# 일단 영상 추출 후 images 폴더에 저장되는 구조.
# 추후에 OCI 저장소에 바로 업로드하는 구조로 변경 필요

from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory


class PreprocessError(Exception):
    """프레임 추출 실패"""


def extract_frames(video_path: Path, output_dir: Path, *, fps: int = 6) -> int:
    """영상에서 JPG를 추출 후, output_dir에 저장함"""

    if not video_path.is_file():
        raise PreprocessError("영상 파일이 없습니다")
    
    if fps <= 0:
        raise PreprocessError("프레임 속도는 양수여야 합니다")
    
    if output_dir.exists():
        raise PreprocessError("프레임 출력 디렉터리가 이미 존재합니다")

    output_dir.parent.mkdir(parents=True, exist_ok=True)
    # 같은 작업 폴더에서 임시로 추출하고 성공한 이미지 디렉터리만 공개

    with TemporaryDirectory(prefix=".preprocess-", dir=output_dir.parent) as temporary:
        # 임시 디렉터리 생성 (작업 끝나면 자동 삭제)
        staged = Path(temporary) / "images"
        staged.mkdir()

        # 현재 6fps로 고정되어 있음.
        # 추후 빠르게 지나가는 구간 보완 예정
        command = [
            "ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error", "-xerror",
            "-protocol_whitelist", "file", "-format_whitelist", "mov,matroska",
            "-i", str(video_path.resolve()),
            "-map", "0:V:0", "-vf", f"fps={fps}", "-q:v", "2", "frame_%06d.jpg",
        ]

        try:
            subprocess.run(command, cwd=staged, check=True, capture_output=True, timeout=1800)
        except FileNotFoundError:
            raise PreprocessError("FFmpeg가 설치되지 않았습니다") from None
        except subprocess.TimeoutExpired:
            raise PreprocessError("FFmpeg 프레임 추출이 시간 초과되었습니다") from None
        except subprocess.CalledProcessError as error:
            lines = error.stderr.decode(errors="replace").strip().splitlines()
            detail = lines[-1] if lines else f"exit status {error.returncode}"
            raise PreprocessError(f"FFmpeg 프레임 추출 실패: {detail}") from None

        count = sum(1 for _ in staged.glob("frame_*.jpg"))
        if not count:
            raise PreprocessError("요청된 프레임 속도로 생성된 프레임이 없습니다")
        staged.rename(output_dir)
    return count
