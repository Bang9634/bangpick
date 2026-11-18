# 서비스 간 계약

기본 경로는 `/api`입니다. `GET /api/actuator/health`는 DB 연결 상태를 포함하며 `/api/actuator/health/liveness`는 생존 확인 경로입니다.

## 영상·PLY 전송

파일 본문은 브라우저·Worker와 오브젝트 스토리지 사이에서 직접 전송됩니다. 백엔드는 상태·소유자를 기록하고 지정한 객체에 대한 임시 접근을 발급합니다. 이 기능의 [개발 환경 설정](../backend/README.md#oci-파일-전송-개발-환경)은 별도로 켜야 합니다.

API 호출 순서와 파일이 이동하는 방향은 [OCI 전송 흐름도](../docs/architecture.md#oci-영상ply-전송)에 있습니다.

개발 중에는 브라우저 API와 내부 Worker API에 서로 다른 `Authorization: Bearer <token>`이 필요합니다. 브라우저 토큰은 하나의 개발 소유자에 대응합니다. Worker는 공유 개발 Worker 토큰으로 인증하고 특정 작업을 점유할 때 받은 `leaseId`로 그 작업에 대한 권한을 증명합니다. 만료 작업의 수동 복구에는 선택적으로 설정하는 별도 운영자 토큰이 필요합니다. 어느 토큰도 스토리지 요청에 전달하지 않습니다.

| 호출자 | HTTP·경로 | 요청 JSON | 성공 응답 |
| --- | --- | --- | --- |
| 브라우저 | `POST /api/media/jobs` | `{contentType, sizeBytes}` | `{jobId, transfer}` |
| 브라우저 | `POST /api/media/jobs/{jobId}/input-complete` | 본문 없음 | `{jobId, status}` |
| 브라우저 | `GET /api/media/jobs/{jobId}` | 본문 없음 | `{jobId, status}` |
| 브라우저 | `POST /api/media/jobs/{jobId}/preview` | 본문 없음 | `transfer` |
| Worker | `POST /api/internal/media/jobs/claim-next` | 본문 없음 | `{jobId, leaseId, leaseExpiresAt}` 또는 대기 작업이 없으면 `204 No Content` |
| Worker | `POST /api/internal/media/jobs/{jobId}/claim` | `{leaseId: null}` 또는 기존 `leaseId` | `{leaseId, leaseExpiresAt, extension, transfer}` |
| Worker | `POST /api/internal/media/jobs/{jobId}/input-failed` | `{leaseId, retryable}` | `{jobId, status}` (`QUEUED` 또는 `FAILED`) |
| Worker | `POST /api/internal/media/jobs/{jobId}/processing-failed` | `{leaseId, retryable}` | `{jobId, status}` (`QUEUED` 또는 `FAILED`) |
| 운영자 | `POST /api/internal/media/jobs/{jobId}/recover-expired` | `{leaseId, previousExecutionStopped: true, retryable}` | `{jobId, status}` (`QUEUED` 또는 `FAILED`) |
| Worker | `POST /api/internal/media/jobs/{jobId}/heartbeat` | `{leaseId}` | `{leaseExpiresAt}` |
| Worker | `POST /api/internal/media/jobs/{jobId}/result-upload` | `{leaseId, sizeBytes}` | `transfer` |
| Worker | `POST /api/internal/media/jobs/{jobId}/result-complete` | `{leaseId}` | `{jobId, status}` |

`transfer`는 `{url, method, expiresAt, sizeBytes}`입니다. `url` 전체를 변경 없이 사용합니다. `PUT`은 multipart 폼이 아닌 **파일 바이트 자체**를 보냅니다. 만료는 ISO 8601 시각입니다. 전송 URL·토큰을 기록하거나 공유하지 않습니다. 응답에는 `Cache-Control: no-store`가 붙습니다.

원본은 MP4, MOV, WebM·최대 512 MiB, 결과는 PLY·최대 256 MiB입니다. 백엔드는 URL 발급 시 요청 크기를 검증하고, 완료 보고 시 저장 객체의 크기와 비교합니다. PAR 자체는 파일 크기를 강제하지 않습니다. 백엔드의 확인 범위는 객체 메타데이터이며, 영상 디코딩이나 파일 내용 검사는 포함하지 않습니다.

- 상태: `UPLOADING → QUEUED → PROCESSING → RESULT_READY`. 입력 다운로드 또는 처리 실패를 보고하면 `PROCESSING → QUEUED`(재시도) 또는 `PROCESSING → FAILED`(최종 실패)가 됩니다. `RESULT_READY`는 **파일 저장 확인**을 뜻하며, 소유자에게만 미리보기를 허용합니다. 검수 승인이나 공개를 의미하지 않습니다.
- 자동 점유는 실행 가능한 `QUEUED` 작업을 `queued_at`, 작업 ID 순으로 선택하고 한 트랜잭션에서 `PROCESSING`으로 바꿉니다. `available_at`이 미래인 작업과 다른 Worker가 잠근 행은 점유하지 않습니다. 선택 후 받은 `leaseId`로 지정 작업의 `/claim`을 호출하면 원본 읽기 URL을 받습니다. 작업 ID를 지정하고 `leaseId: null`을 보내는 기존 수동 점유는 `QUEUED` 작업에만 허용하며, 이 경로에는 대기 순서가 적용되지 않습니다. 만료된 `PROCESSING` 작업은 기존 점유 ID나 새 점유 요청으로 재점유할 수 없고 `409`를 반환합니다.
- 새 점유마다 `attempt_count`가 증가하고 최대 3번 점유할 수 있습니다. `input-failed`와 `processing-failed`는 현재 유효한 점유 ID로 결과 업로드 전에만 호출할 수 있습니다. `retryable: true`이면 첫 실패 후 30초, 두 번째 실패 후 60초에 다시 선택할 수 있고 세 번째에는 `FAILED`가 됩니다. `retryable: false`이면 바로 `FAILED`입니다. 같은 점유 ID·실패 종류의 보고를 재요청해도 상태를 다시 변경하지 않습니다. 두 종류는 DB에 각각 `INPUT_DOWNLOAD_FAILED`, `PROCESSING_FAILED`로 기록하며, 다른 종류로 재요청하면 거부합니다. 대기 시각은 초 단위이므로 같은 초에 대기 상태가 된 작업은 작업 ID 순으로 처리합니다. 이 API만으로 GPU 전체의 동시 실행 수가 제한되거나 만료 작업이 자동 복구되지는 않습니다. V2 이전 대기 작업의 `queued_at = 0`은 실제 접수 시각이 아니라 시각 미상 표시값입니다.
- URL은 읽기 5분, 쓰기 최대 15분입니다. Worker 점유는 6시간이며 활성 점유 ID를 보관해야 합니다. 점유 만료만으로는 이전 GPU 계산이 중단됐다고 보지 않으므로 작업은 `PROCESSING`에 남습니다. 이전 실행이 중단됐음을 운영자가 확인했을 때만 별도 복구 토큰으로 `recover-expired`를 호출합니다. `previousExecutionStopped: true`는 필수 선언이며 백엔드가 GPU 실행을 직접 검증하지는 않습니다. 기존 점유 ID가 일치하고 이미 만료됐을 때에만 복구할 수 있습니다. `retryable: true`는 남은 시도가 있으면 기존 30초·60초 지연 후 `QUEUED`, 한도에 도달하면 `FAILED`로 전환하며 `false`는 즉시 `FAILED`입니다. 복구 시 이전 결과 쓰기 권한을 회수하고 결과 키를 지웁니다. 같은 점유 ID의 복구 요청은 중복 적용하지 않고, 새 점유가 시작된 뒤에는 이전 요청을 거부합니다. 결과 URL을 재발급하면 새 저장 키를 사용합니다.
- `heartbeat`는 현재 `PROCESSING` 작업의 유효한 점유 ID에만 허용하고, 점유 만료 시각을 최소 호출 시점 6시간 뒤까지 연장합니다. 이미 만료됐거나 완료·실패한 작업의 점유는 되살리지 않습니다. 점유 횟수는 늘어나지 않습니다. Worker의 단발 명령은 한 번만 갱신하고, `run-process`는 로컬 처리 명령 실행 중 60초마다 호출합니다.
- 상태 코드는 요청 형식 오류 `400`, 인증 실패 `401`, 없거나 소유자가 아닌 작업 `404`, 미완료·권한 없는 점유·크기 불일치 등 상태 충돌 `409`, 스토리지 오류 `502`입니다. 완료 요청은 동일 작업/점유에서 다시 호출할 수 있습니다. 잘못된 원본을 다시 올리려면 새 작업을 생성합니다.
