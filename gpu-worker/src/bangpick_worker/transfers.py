"""Backend control requests and direct object transfers for an assigned job."""

import json
import re
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

CHUNK_SIZE = 1024 * 1024


class TransferError(Exception):
    """A user-facing failure that never includes a grant URL or credential."""


class PermanentTransferError(TransferError):
    """An input response that cannot be fixed by retrying the same transfer."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # 백엔드 토큰이나 임시 URL이 다른 주소로 전달되는 것을 막는다.
        return None


class Transfers:
    def __init__(self, api: str, token: str):
        url = urlsplit(api)
        if (
            not url.hostname
            or url.username is not None
            or url.password is not None
            or url.query
            or url.fragment
            or url.path.rstrip("/") != "/api"
            or (
                url.scheme != "https"
                and not (url.scheme == "http" and url.hostname in {"127.0.0.1", "::1", "localhost"})
            )
        ):
            raise TransferError("BANGPICK_API_URL must end in /api and use HTTPS or loopback HTTP")
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", token):
            raise TransferError("Set BANGPICK_MEDIA_WORKER_TOKEN")
        self.api = api.rstrip("/")
        self.token = token
        self.opener = build_opener(NoRedirect())

    def claim_next(self) -> dict | None:
        request = Request(
            f"{self.api}/internal/media/jobs/claim-next",
            method="POST",
            data=b"",
            headers={"Authorization": f"Bearer {self.token}"},
        )
        with self._open(request, "Backend", 45) as response:
            if response.status == 204:
                return None
            if response.status != 200:
                raise TransferError("Backend returned an unexpected response")
            try:
                result = json.load(response)
            except ValueError:
                raise TransferError("Backend returned an invalid response") from None
        if not isinstance(result, dict):
            raise TransferError("Backend returned an invalid response")
        return result

    def command(self, job_id: str, action: str, payload: dict) -> dict:
        request = Request(
            f"{self.api}/internal/media/jobs/{job_id}/{action}",
            method="POST",
            data=json.dumps(payload).encode(),
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"},
        )
        with self._open(request, "Backend", 45) as response:
            try:
                result = json.load(response)
            except ValueError:
                raise TransferError("Backend returned an invalid response") from None
        if not isinstance(result, dict):
            raise TransferError("Backend returned an invalid response")
        return result

    def download(self, grant: dict, target: Path) -> None:
        url, size = self._grant(grant, "GET", 512 * CHUNK_SIZE)
        # 예상 크기만큼 모두 받기 전에는 기존 파일을 교체하지 않는다.
        temporary_path = None
        try:
            with self._open(Request(url), "Input download", 60) as response:
                with NamedTemporaryFile(dir=target.parent, delete=False) as temporary:
                    temporary_path = Path(temporary.name)
                    remaining = size
                    while remaining:
                        chunk = response.read(min(CHUNK_SIZE, remaining))
                        if not chunk:
                            raise TransferError("Input download was incomplete")
                        temporary.write(chunk)
                        remaining -= len(chunk)
                    if response.read(1):
                        raise TransferError("Input exceeds the verified size")
            temporary_path.replace(target)
        finally:
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)

    def upload(self, grant: dict, path: Path) -> None:
        url, size = self._grant(grant, "PUT", 256 * CHUNK_SIZE)
        if path.stat().st_size != size:
            raise TransferError("The result size changed before upload")
        with path.open("rb") as file:
            # 임시 URL에는 multipart 폼이 아니라 파일 바이트를 그대로 PUT한다.
            request = Request(
                url, data=file, method="PUT",
                headers={"Content-Length": str(size), "Content-Type": "application/octet-stream"},
            )
            with self._open(request, "Result upload", 120):
                pass

    def _open(self, request: Request, stage: str, timeout: int):
        try:
            return self.opener.open(request, timeout=timeout)
        except HTTPError as error:
            status = error.code
            error.close()
            raise TransferError(f"{stage} failed (HTTP {status})") from None
        except (URLError, TimeoutError, OSError):
            raise TransferError(f"{stage} failed (network or TLS error)") from None

    @staticmethod
    def _grant(grant: dict, method: str, limit: int) -> tuple[str, int]:
        url, size = grant.get("url"), grant.get("sizeBytes")
        if not isinstance(url, str):
            raise PermanentTransferError("Invalid object transfer URL")
        parsed = urlsplit(url)
        if (grant.get("method") != method or parsed.scheme != "https" or not parsed.hostname
                or parsed.username is not None or parsed.password is not None
                or parsed.fragment or type(size) is not int or not 0 < size <= limit):
            raise PermanentTransferError("Invalid object transfer grant")
        return url, size
