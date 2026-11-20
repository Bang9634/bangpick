# 서비스 간 계약

기본 경로는 `/api`입니다. `GET /api/actuator/health`는 DB 연결 상태를 포함하며 `/api/actuator/health/liveness`는 생존 확인 경로입니다.

## 회원 세션과 관리자 API

브라우저는 `/api/auth/options`가 제공하는 `loginPath`로 이동해 Google 로그인을 시작합니다. 이 절대 URL은 `BANGPICK_WEB_ORIGIN`과 `/api/oauth2/authorization/google`을 합친 주소입니다. Google 인증을 마친 브라우저는 같은 웹 주소의 콜백으로 돌아옵니다. 브라우저는 API 요청에 세션 쿠키를 사용하고, 상태를 바꾸는 요청에는 `/api/auth/csrf`에서 받은 토큰을 지정된 헤더에 추가합니다.

| HTTP·경로 | 접근 권한 | 성공 응답 |
| --- | --- | --- |
| `GET /api/auth/options` | 누구나 | `{loginPath}` · 절대 URL 또는 로그인 비활성화 시 `null` |
| `GET /api/me` | 로그인한 회원 | `{id, displayName, role}` · 역할은 `USER` 또는 `ADMIN` |
| `GET /api/auth/csrf` | 로그인한 회원 | `{headerName, token}` |
| `POST /api/auth/logout` | 세션의 CSRF 토큰 필요 | `204` |
| `GET /api/admin/access` | 관리자 | `204` |

관리자 API는 `/api/admin/**` 아래에 두며 백엔드가 요청 시점의 DB 역할을 확인합니다. 세션 없이 관리자 조회 시 `401`, 일반 회원이 접근하거나 필요한 CSRF 검증에 실패하면 `403`입니다.

## 영상·PLY 전송

파일 본문은 브라우저·Worker와 오브젝트 스토리지 사이에서 직접 전송됩니다. 백엔드는 상태·소유자를 기록하고 지정한 객체에 대한 임시 접근을 발급합니다. 이 기능의 [개발 환경 설정](../backend/README.md#oci-파일-전송-개발-환경)은 별도로 켜야 합니다.

API 호출 순서와 파일이 이동하는 방향은 [OCI 전송 흐름도](../docs/architecture.md#oci-영상ply-전송)에 있습니다.

브라우저 API는 회원 세션으로 인증하고 작업의 소유자 ID를 확인합니다. Google 로그인을 끈 로컬 환경에서는 브라우저의 개발 토큰을 공통 개발 소유자로 처리합니다. 내부 Worker API는 별도의 `Authorization: Bearer <token>`을 받고, 작업 점유 후에는 `leaseId`도 검증합니다. 백엔드의 세션 쿠키와 토큰은 스토리지 요청에 전달하지 않습니다.

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

원본은 MP4, MOV, WebM·최대 512 MiB, 결과는 PLY·최대 256 MiB입니다. 백엔드는 URL 발급 시 요청 크기를 검증하고, 완료 보고 시 저장 객체의 크기와 비교합니다. PAR 자체는 파일 크기를 강제하지 않습니다. 백엔드의 확인 범위는 객체 메타데이터이며, 영상 디코딩이나 파일 내용 검사는 포함하지 않습니다.

- 상태: `UPLOADING → QUEUED → PROCESSING → RESULT_READY`. `RESULT_READY`는 **파일 저장 확인**을 뜻하며, 소유자에게만 미리보기를 허용합니다. 검수 승인이나 공개를 의미하지 않습니다.
- URL은 읽기 5분, 쓰기 최대 15분입니다. Worker 점유는 6시간이며 활성 점유 ID를 보관해야 합니다. 점유 만료 후 다른 실행이 작업을 다시 받을 수 있습니다. 결과 URL을 재발급하면 새 저장 키를 사용합니다.
- 상태 코드는 요청 형식 오류 `400`, 인증 실패 `401`, 없거나 소유자가 아닌 작업 `404`, 미완료·권한 없는 점유·크기 불일치 등 상태 충돌 `409`, 스토리지 오류 `502`입니다. 완료 요청은 동일 작업/점유에서 다시 호출할 수 있습니다. 잘못된 원본을 다시 올리려면 새 작업을 생성합니다.

백엔드와 Worker의 자동 배정 구현을 위한 제안은 [재구성 작업 큐 계약 초안](worker-queue.md)에 있습니다.
