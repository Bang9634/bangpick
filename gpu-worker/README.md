# GPU Worker

Worker는 작업 하나의 원본 영상과 결과 PLY를 전송하고, 로컬 처리 명령을 실행하는 동안 점유를 갱신하는 CLI입니다. 대기 중인 작업을 하나 골라 점유하거나 작업 ID를 직접 지정할 수 있습니다. 전송 및 감시 기능 자체에는 GPU가 필요하지 않습니다.

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
.venv/bin/bangpick-worker process-next --work-dir ../outputs/worker -- python /path/to/processor.py
.venv/bin/bangpick-worker download <job-id> --work-dir ../outputs/worker
.venv/bin/bangpick-worker heartbeat <job-id> --work-dir ../outputs/worker
.venv/bin/bangpick-worker run-process <job-id> --work-dir ../outputs/worker -- python /path/to/processor.py
.venv/bin/bangpick-worker processing-failed <job-id> --work-dir ../outputs/worker
.venv/bin/bangpick-worker upload <job-id> ./room.ply --work-dir ../outputs/worker
```

`download-next`는 백엔드의 `claim-next`를 한 번 호출합니다. 대기 작업이 없으면 `No queued job`을 출력하고 정상 종료합니다. 작업이 있으면 `<job-id>/lease`에 점유 ID를 먼저 저장하고 `<job-id>/source.*`에 영상을 다운로드합니다. 현재 명령은 한 번 실행하고 종료하며 학습을 시작하지 않습니다.

`process-next`는 잠금을 잡은 뒤 작업 1건을 점유하고 다운로드·처리 명령·PLY 확인·업로드·완료 보고까지 수행한 후 종료합니다. 대기 작업이 없으면 처리 명령을 실행하지 않습니다. 처리 명령의 현재 디렉터리는 `<work-dir>/<job-id>`이며, 입력 영상 절대 경로는 `BANGPICK_INPUT_VIDEO`, 결과 PLY 절대 경로는 `BANGPICK_OUTPUT_PLY` 환경 변수로 전달합니다. 결과는 점유 ID별 `<job-id>/attempts/<lease-id>/room.ply`에 만들어 이전 시도의 파일과 분리합니다. 처리 명령은 PLY 쓰기를 마치고 파일을 닫은 다음 종료 코드 0으로 끝나야 합니다. 실제 전처리·3DGS 학습 명령은 아직 연결되지 않았습니다.

`process-next`는 처리 명령의 시작 실패·비정상 종료 또는 PLY 누락·기본 형식 오류를 `processing-failed`로 보고합니다. 기본은 `FAILED`이며, 일시 오류가 예상될 때는 `--retryable`을 `--` 앞에 붙입니다. PLY 업로드나 완료 보고가 실패하면 작업을 재대기시키지 않고 점유 파일과 시도별 PLY를 보존합니다. 출력된 경로로 `upload <job-id> <PLY 경로> --work-dir ...`를 다시 실행하거나, 업로드가 끝났고 완료 응답만 실패했다면 `complete <job-id> --work-dir ...`를 사용합니다. `process-next`는 PLY의 존재·크기·첫 줄 `ply`만 확인하며 웹 렌더링 호환성까지 검증하지는 않습니다.

`download-next`에서 입력 URL 요청이나 원본 다운로드가 실패하면 점유 ID로 `input-failed`를 보고합니다. 일시 오류는 백엔드가 30초·60초 뒤 재시도하도록 대기시키고, 세 번째 실패나 다시 받아도 해결되지 않는 응답 오류는 `FAILED`로 기록합니다. 이때 명령은 오류로 종료합니다. `QUEUED`로 돌아간 작업은 대기 시간이 지난 뒤 `download-next`로 다시 가져올 수 있습니다. 실패 보고까지 실패했다면 점유 파일을 보존하므로 출력된 작업 ID로 `download <job-id> --work-dir ...`를 재시도할 수 있습니다. 작업 ID를 직접 지정한 `download` 명령은 자동 실패 보고를 하지 않습니다.

`lease` 파일에는 마지막으로 받은 점유 ID가 남습니다. 실패 보고 후에는 그 ID가 더 이상 유효하지 않으며, 작업을 다시 점유하면 새 ID로 덮어씁니다.

만료된 `PROCESSING` 작업은 `download <job-id>`로도 새 점유를 받을 수 없습니다. 이전 실행의 종료를 확인한 운영자가 [백엔드의 수동 복구 절차](../backend/README.md#만료-작업-수동-복구)를 수행해야 합니다. Worker 토큰으로 복구 API를 호출할 수 없습니다.

`heartbeat`는 저장된 점유 ID로 백엔드에 한 번 갱신을 요청하고 새 만료 시각을 출력합니다. 이미 만료된 점유는 갱신할 수 없습니다.

`run-process`는 이미 다운로드해 점유한 작업에 대해 `--` 뒤의 **신뢰할 수 있는 로컬 명령**을 작업 폴더에서 실행합니다. 실행 직전에 heartbeat를 보내고, 명령이 살아 있는 동안 60초마다 다시 보냅니다. 명령에는 Worker API 토큰을 환경 변수로 전달하지 않습니다. 같은 호스트에서 같은 `--work-dir`를 사용하는 명령은 파일 잠금으로 동시에 실행되지 않습니다. 처리 명령이 끝나면 감시도 끝나므로 이후 PLY 업로드와 완료 보고는 별도로 실행해야 합니다.

처리 명령을 시작하지 못했거나 0이 아닌 코드로 끝나면 `run-process`가 `processing-failed`를 백엔드에 보고합니다. 기본은 `FAILED`이고, 일시적인 오류임을 알고 있다면 `run-process <job-id> --work-dir ... --retryable -- <명령>`으로 실행해 최대 3회까지 재시도합니다. 첫 실패 후 30초, 두 번째 실패 후 60초가 지나야 다시 선택됩니다. 실패 보고의 응답을 받지 못했다면 **처리 명령이 실제로 멈춘 것을 확인한 뒤** `processing-failed <job-id> --work-dir ...`로 같은 점유 ID의 보고만 재시도할 수 있습니다. 원래 `--retryable`로 실행했다면 이 명령에도 `--retryable`을 붙입니다. 이미 보고된 실패를 같은 종류로 재요청해도 상태는 다시 바뀌지 않습니다.

heartbeat 실패나 중단 시에는 직접 실행한 자식 프로세스에 종료를 요청하지만, 그 프로세스가 따로 만든 하위 프로세스의 종료까지 보장하지 않습니다. 따라서 이 경우 실패를 자동 보고하지 않고 작업은 `PROCESSING`에 남을 수 있습니다. `process-next`는 점유 전에 잠금을 잡아 완료 보고까지 유지하고, `download-next`·`download`·`upload`·`complete`도 각 명령이 실행되는 동안 같은 잠금을 사용합니다. 수동 명령을 순서대로 여러 번 실행하면 작업을 여러 건 점유할 수 있으며, 다른 작업 디렉터리나 GPU 호스트의 실행도 이 잠금으로 제한되지 않습니다. 백엔드 전체의 GPU 동시 실행 수 제한은 별도 작업입니다.

작업 폴더·실제 모델·개발용 토큰을 커밋하지 않습니다. 이미 PLY 업로드가 끝났는데 완료 응답만 실패했다면 `complete <job-id> --work-dir ../outputs/worker`를 실행합니다. PLY 업로드가 실패했다면 `upload`를 재실행해 새 URL을 받습니다.

백엔드 API와 Worker는 JSON을 주고받고, 파일은 Worker가 OCI와 HTTPS로 직접 전송합니다. Worker에 OCI 키를 복사하거나 수신 포트를 열 필요는 없습니다. [전송 계약](../contracts/README.md#영상ply-전송) 참고.
