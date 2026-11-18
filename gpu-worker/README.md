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
.venv/bin/bangpick-worker heartbeat <job-id> --work-dir ../outputs/worker
.venv/bin/bangpick-worker upload <job-id> ./room.ply --work-dir ../outputs/worker
```

`download-next`는 백엔드의 `claim-next`를 한 번 호출합니다. 대기 작업이 없으면 `No queued job`을 출력하고 정상 종료합니다. 작업이 있으면 `<job-id>/lease`에 점유 ID를 먼저 저장하고 `<job-id>/source.*`에 영상을 다운로드합니다. 현재 명령은 한 번 실행하고 종료하며 학습을 시작하지 않습니다.

`download-next`에서 입력 URL 요청이나 원본 다운로드가 실패하면 점유 ID로 `input-failed`를 보고합니다. 일시 오류는 백엔드가 30초·60초 뒤 재시도하도록 대기시키고, 세 번째 실패나 다시 받아도 해결되지 않는 응답 오류는 `FAILED`로 기록합니다. 이때 명령은 오류로 종료합니다. `QUEUED`로 돌아간 작업은 대기 시간이 지난 뒤 `download-next`로 다시 가져올 수 있습니다. 실패 보고까지 실패했다면 점유 파일을 보존하므로 출력된 작업 ID로 `download <job-id> --work-dir ...`를 재시도할 수 있습니다. 작업 ID를 직접 지정한 `download` 명령은 자동 실패 보고를 하지 않습니다.

`lease` 파일에는 마지막으로 받은 점유 ID가 남습니다. 실패 보고 후에는 그 ID가 더 이상 유효하지 않으며, 작업을 다시 점유하면 새 ID로 덮어씁니다.

`heartbeat`는 저장된 점유 ID로 백엔드에 한 번 갱신을 요청하고 새 만료 시각을 출력합니다. 이미 만료된 점유는 갱신할 수 없습니다. 현재 CLI에는 주기적인 자동 호출이나 학습 프로세스 감시가 없으므로, 이 명령만으로 장시간 작업의 점유 유지가 자동화되지는 않습니다.

작업 폴더·실제 모델·개발용 토큰을 커밋하지 않습니다. 이미 PLY 업로드가 끝났는데 완료 응답만 실패했다면 `complete <job-id> --work-dir ../outputs/worker`를 실행합니다. PLY 업로드가 실패했다면 `upload`를 재실행해 새 URL을 받습니다.

백엔드 API와 Worker는 JSON을 주고받고, 파일은 Worker가 OCI와 HTTPS로 직접 전송합니다. Worker에 OCI 키를 복사하거나 수신 포트를 열 필요는 없습니다. [전송 계약](../contracts/README.md#영상ply-전송) 참고.
