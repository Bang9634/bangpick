# GPU Worker

Worker는 작업 하나의 원본 영상과 결과 PLY를 전송하는 CLI입니다. 대기 중인 작업을 하나 골라 점유하거나 작업 ID를 직접 지정할 수 있습니다. 명령 실행에 GPU는 필요하지 않습니다.

Python 3.11 이상에서 `pyproject.toml`이 있는 디렉토리에서 환경을 준비합니다.

```sh
python -m venv .venv
.venv/bin/python -m pip install -e .
```

Windows에서는 `.venv\Scripts\python.exe -m pip install -e .`를 실행하고, 아래의 `.venv/bin/bangpick-worker` 대신 `.venv\Scripts\python.exe -m bangpick_worker`를 사용합니다.

Worker 실행 환경에 `BANGPICK_API_URL`과 `BANGPICK_MEDIA_WORKER_TOKEN`을 지정합니다. API 주소는 `/api`로 끝나야 하고, 같은 컴퓨터 또는 터널의 루프백 주소만 HTTP를 허용합니다. 예를 들어 맥에서 백엔드를 켜고 윈도우에서 보호된 터널을 통해 맥의 8080으로 연결하면 Worker 주소는 터널의 로컬 포트가 됩니다. 원격 API 주소를 직접 사용하려면 HTTPS가 필요합니다.

[영상 업로드 API](../contracts/README.md#영상ply-전송)가 반환한 작업 UUID를 직접 지정하거나, `download-next`로 실행 가능한 대기 작업을 하나 가져옵니다. 결과 업로드에는 선택된 작업 UUID와 같은 작업 폴더를 사용합니다.

```sh
.venv/bin/bangpick-worker download-next --work-dir ../outputs/worker
.venv/bin/bangpick-worker download <job-id> --work-dir ../outputs/worker
.venv/bin/bangpick-worker upload <job-id> ./room.ply --work-dir ../outputs/worker
```

`download-next`는 백엔드의 `claim-next`를 한 번 호출합니다. 대기 작업이 없으면 `No queued job`을 출력하고 정상 종료합니다. 작업이 있으면 `<job-id>/lease`에 점유 ID를 먼저 저장하고 `<job-id>/source.*`에 영상을 다운로드합니다. 다운로드가 실패했다면 출력된 작업 ID 또는 생성된 폴더 이름으로 `download <job-id> --work-dir ...`를 다시 실행할 수 있습니다. 현재 명령은 한 번 실행하고 종료하며 학습을 시작하지 않습니다.

작업 폴더·실제 모델·개발용 토큰을 커밋하지 않습니다. 이미 PLY 업로드가 끝났는데 완료 응답만 실패했다면 `complete <job-id> --work-dir ../outputs/worker`를 실행합니다. PLY 업로드가 실패했다면 `upload`를 재실행해 새 URL을 받습니다.

백엔드 API와 Worker는 JSON을 주고받고, 파일은 Worker가 OCI와 HTTPS로 직접 전송합니다. Worker에 OCI 키를 복사하거나 수신 포트를 열 필요는 없습니다. [전송 계약](../contracts/README.md#영상ply-전송) 참고.
