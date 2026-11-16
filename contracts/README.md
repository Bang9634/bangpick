# 서비스 간 계약

기본 경로는 `/api`입니다. `GET /api/actuator/health`는 DB 연결 상태를 포함하며 `/api/actuator/health/liveness`는 생존 확인 경로입니다.

## 영상·PLY 전송

파일 본문은 브라우저·Worker와 오브젝트 스토리지 사이에서 직접 전송됩니다. 백엔드는 상태·소유자를 기록하고 지정한 객체에 대한 임시 접근을 발급합니다. 이 기능의 [개발 환경 설정](../backend/README.md#oci-파일-전송-개발-환경)은 별도로 켜야 합니다.

개발 중에는 브라우저 API와 내부 Worker API에 서로 다른 `Authorization: Bearer <token>`이 필요합니다. 브라우저 토큰은 하나의 개발 소유자에 대응합니다. Worker는 공유 개발 Worker 토큰으로 인증하고 특정 작업을 점유할 때 받은 `leaseId`로 그 작업에 대한 권한을 증명합니다. 두 토큰을 스토리지 요청에 전달하지 않습니다.

| 호출자 | HTTP·경로 | 요청 JSON | 성공 응답 |
| --- | --- | --- | --- |
| 브라우저 | `POST /api/media/jobs` | `{contentType, sizeBytes}` | `{jobId, transfer}` |
| 브라우저 | `POST /api/media/jobs/{jobId}/input-complete` | 본문 없음 | `{jobId, status}` |
| 브라우저 | `GET /api/media/jobs/{jobId}` | 본문 없음 | `{jobId, status}` |
| 브라우저 | `POST /api/media/jobs/{jobId}/preview` | 본문 없음 | `transfer` |
| Worker | `POST /api/internal/media/jobs/{jobId}/claim` | `{leaseId: null}` 또는 기존 `leaseId` | `{leaseId, leaseExpiresAt, extension, transfer}` |
| Worker | `POST /api/internal/media/jobs/{jobId}/result-upload` | `{leaseId, sizeBytes}` | `transfer` |
| Worker | `POST /api/internal/media/jobs/{jobId}/result-complete` | `{leaseId}` | `{jobId, status}` |

`transfer`는 `{url, method, expiresAt, sizeBytes}`입니다. `url` 전체를 변경 없이 사용합니다. `PUT`은 multipart 폼이 아닌 **파일 바이트 자체**를 보냅니다. 만료는 ISO 8601 시각입니다. 전송 URL·토큰을 기록하거나 공유하지 않습니다. 응답에는 `Cache-Control: no-store`가 붙습니다.

원본은 MP4, MOV, WebM·최대 512 MiB, 결과는 PLY·최대 256 MiB입니다. 제한은 URL 발급 요청과 업로드 완료 시 저장 객체 크기에 적용됩니다. PAR 자체가 최대 길이를 보장하지 않습니다. 백엔드는 파일 내용을 분석하지 않으며 영상 디코딩·안전 검증은 자동 GPU 처리와 함께 추가해야 합니다.

- 상태: `UPLOADING → QUEUED → PROCESSING → RESULT_READY`. 마지막은 **저장 확인**을 뜻합니다. 소유자의 미리보기만 가능하며 검수·공개는 아직 없습니다.
- URL은 읽기 5분, 쓰기 최대 15분입니다. Worker 점유는 6시간이며 활성 점유 ID를 보관해야 합니다. 점유 만료 후 다른 실행이 작업을 다시 받을 수 있습니다. 결과 URL을 재발급하면 새 저장 키를 사용합니다.
- 상태 코드는 요청 형식 오류 `400`, 인증 실패 `401`, 없거나 소유자가 아닌 작업 `404`, 미완료·권한 없는 점유·크기 불일치 등 상태 충돌 `409`, 스토리지 오류 `502`입니다. 완료 요청은 동일 작업/점유에서 다시 호출할 수 있습니다. 잘못된 원본을 다시 올리려면 새 작업을 생성합니다.
