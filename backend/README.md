# bangpick 백엔드

Java 21과 Maven Wrapper로 실행합니다. Windows에서는 `mvnw.cmd`를 사용하세요. 전체 구성은 [개발 안내](../docs/development.md)에 있습니다.

```sh
./mvnw verify
./mvnw spring-boot:run
```

개별 실행 시 기본 DB는 `localhost:13306/bangpick`입니다. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`는 셸·IDE 환경에서 지정하세요. JVM은 저장소 루트의 `.env`를 자동으로 읽지 않습니다. DB는 [인프라 안내](../infra/README.md)대로 기동하며, 상태는 `http://127.0.0.1:8080/api/actuator/health`에서 확인합니다.

## Google 로그인 개발 환경

1. Google Auth Platform에서 **웹 애플리케이션** 유형의 OAuth 클라이언트를 만듭니다. Vite와 Docker Compose의 기본 웹 주소는 모두 `http://localhost:8088`이므로, 승인된 리디렉션 URI에 `http://localhost:8088/api/login/oauth2/code/google`을 등록합니다. 동의 화면을 테스트 모드로 운영하는 경우 로그인할 Google 계정을 테스트 사용자에 추가합니다. [Google OAuth 설정](https://developers.google.com/identity/protocols/oauth2/web-server)

2. [`.env.example`](../.env.example)을 참고해 `BANGPICK_AUTH_ENABLED=true`, `BANGPICK_WEB_ORIGIN`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`을 설정합니다. Compose는 값을 백엔드에 전달합니다. Maven으로 실행할 때는 아래 OCI 실행 예시처럼 먼저 `.env`를 셸에 불러옵니다. 배포 환경에서는 실제 HTTPS 웹 주소를 사용합니다.
3. 설정한 브라우저 주소로 접속해 **Google로 로그인**을 누릅니다. 로그인 후 `/api/me`에서 내부 회원 ID와 역할을 확인할 수 있습니다.

### 첫 관리자 지정

지정할 팀원이 먼저 Google로 로그인한 뒤, 해당 팀원의 `/api/me` 응답에서 확인한 내부 회원 ID를 사용해 운영자가 DB에서 권한을 부여합니다.

```sql
UPDATE members SET role = 'ADMIN' WHERE id = '<내부 회원 ID>';
```

관리자 계정은 `/api/admin/access`에서 `204`를 받고, 로그인한 일반 회원은 `403`을 받습니다. 권한을 회수하려면 같은 ID의 역할을 `USER`로 바꿉니다. 이미 로그인한 세션에도 다음 요청부터 적용됩니다.

## 기존 DB에 작업 대기열 마이그레이션 적용 전

V1 또는 V2 스키마를 사용하던 DB를 큐 V3~V5로 올리기 전에 아래 조회로 기존 실행 중 작업을 확인합니다. 새 DB에서 시작한다면 해당하지 않습니다.

```sql
SELECT COUNT(*) AS processing_jobs FROM media_jobs WHERE status = 'PROCESSING';
```

`0`건이면 이 제약 없이 마이그레이션을 진행할 수 있습니다. 기존 `PROCESSING` 행이 있으면 V3에서 `attempt_count=0`으로 채워지지만 현재 `recover-expired`는 1회 이상 점유한 작업만 복구하므로, 해당 행은 점유가 만료돼도 API로 복구할 수 없습니다. 이 경우 기존 실행과 결과의 상태를 확인하고 해당 작업의 처리 방안을 정한 뒤 마이그레이션을 적용합니다. 기존 GPU 실행의 종료를 확인하지 않은 채 시도 횟수만 임의로 바꾸거나 작업을 재실행하지 않습니다. 적용 대상 DB의 기존 행 유무는 별도로 확인해야 합니다.

기존 공유 DB에 이 PR의 옛 큐 V2~V4를 이미 적용했다면 파일명을 바꾸기 전에 `flyway_schema_history`의 적용 버전과 파일명을 확인해야 합니다. 적용 이력을 확인하지 않고 V3~V5를 실행하지 않습니다.

## MySQL 작업 대기열 테스트

기본 `verify`는 H2 기반 테스트를 실행합니다. 실제 MySQL의 `FOR UPDATE SKIP LOCKED`와 만료 작업 수동 복구는 별도의 로컬 `bangpick_queue_test` DB에서만 테스트합니다. 테스트는 Flyway 마이그레이션을 적용하고 자신이 만든 작업 행을 삭제합니다. 개발용 `bangpick` DB나 공유 DB를 테스트 URL로 지정하지 마세요.

아래 명령은 기존 Compose DB와 격리된 일회용 MySQL을 실행합니다. 포트가 이미 사용 중이면 사용하지 않는 로컬 포트로 변경하세요.

```sh
docker run --rm -d --name bangpick-queue-mysql-test -e MYSQL_ROOT_PASSWORD=queue-root-test -e MYSQL_DATABASE=bangpick_queue_test -e MYSQL_USER=queue_test -e MYSQL_PASSWORD=queue-test -p 127.0.0.1:13316:3306 mysql:8.4
```

MySQL 준비가 끝나면 `backend/`에서 테스트 전용 환경변수를 지정하고 `./mvnw '-Dtest=MediaQueueMySqlConcurrencyTests,MediaQueueMySqlRecoveryTests' test`를 실행합니다. Windows에서는 `./mvnw` 대신 `.\mvnw.cmd`를 사용합니다.

| 변수 | 일회용 컨테이너 예시 |
| --- | --- |
| `BANGPICK_QUEUE_TEST_MYSQL_URL` | `jdbc:mysql://127.0.0.1:13316/bangpick_queue_test` |
| `BANGPICK_QUEUE_TEST_MYSQL_USER` | `queue_test` |
| `BANGPICK_QUEUE_TEST_MYSQL_PASSWORD` | `queue-test` |

URL이 로컬 `bangpick_queue_test` DB를 가리키지 않으면 테스트는 실행되지 않습니다. 확인 후 `docker stop bangpick-queue-mysql-test`로 일회용 컨테이너를 종료합니다.

복구 테스트는 테스트 DB의 `lease_expires`를 과거로 바꿔 만료를 재현합니다. 실제 운영에서 이 값을 변경하는 절차가 아닙니다. 백엔드는 GPU 프로세스의 종료를 판별하지 않으므로 운영자가 이전 실행의 종료를 확인해야 합니다.

## 로컬 API·Worker 전송 통합 테스트

`BANGPICK_WORKER_E2E_PYTHON`에 Python 3.11 이상 실행 파일의 경로를 지정한 뒤 `backend/`에서 `./mvnw -Dtest=MediaWorkerLocalFlowTests test`를 실행합니다. Windows에서는 `.\mvnw.cmd`를 사용합니다. 변수 설정이 없으면 이 테스트는 건너뜁니다.

테스트는 임시 H2 DB와 실행 중에 생성한 인증서를 사용하는 로컬 HTTPS 저장소를 띄웁니다. 합성 입력 바이트를 업로드한 뒤 실제 백엔드 API와 `process-next` 명령으로 작업을 처리하고, 결과 조회 URL에서 테스트용 PLY 바이트를 확인합니다. 영상 디코딩·PLY 렌더링·OCI 버킷·GPU 학습은 검증하지 않습니다. 인증서와 작업 파일은 테스트 종료 시 삭제합니다.

## OCI 파일 전송 개발 환경

1. 저장소 루트에서 `docker compose up -d db`로 로컬 MySQL을 실행합니다. 기존 DB를 사용할 경우 아래의 DB 변수만 바꿉니다.
2. OCI 설정 파일에 API 서명 키를 구성하고 로컬 백엔드 사용자만 읽을 수 있게 합니다. 키와 설정을 Git에 추가하지 않습니다. 백엔드의 IAM 권한은 개발 버킷으로 제한하고, 객체 메타데이터 확인과 읽기·쓰기 PAR 생성 및 회수를 허용합니다. [OCI SDK 설정 파일](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/sdkconfig.htm) · [PAR 필요 권한](https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/usingpreauthenticatedrequests.htm#required-permissions)
3. [`.env.example`](../.env.example)의 OCI 항목을 로컬 `.env`에 채웁니다. 이미 `.env`가 있다면 필요한 항목만 추가합니다. 아래의 값을 준비한 뒤 전송 기능을 사용할 때만 `BANGPICK_MEDIA_ENABLED=true`로 바꿉니다.

| 변수 | 개발 예시 |
| --- | --- |
| `BANGPICK_MEDIA_ENABLED` | `true` — 기본값은 `false` |
| `BANGPICK_MEDIA_DEV_TOKEN` | Google 로그인이 꺼진 로컬 환경에서 사용하는 브라우저 개발 토큰. `openssl rand -hex 32`로 생성 |
| `BANGPICK_MEDIA_WORKER_TOKEN` | Worker용 토큰. 위와 별도로 한 번 더 생성 |
| `BANGPICK_MEDIA_RECOVERY_TOKEN` | 선택 사항. 이전 실행 중단을 확인한 운영자의 만료 작업 복구 전용 토큰. 다른 두 토큰과 별도로 생성 |
| `OCI_CONFIG_FILE` | OCI SDK 설정 파일의 절대 경로 |
| `OCI_CONFIG_PROFILE` | 개발용 OCI 프로필 이름, 예: `BANGPICK_DEV` |
| `OCI_REGION` | 개발 버킷의 리전 ID |
| `OCI_NAMESPACE` | 버킷 상세 화면의 Object Storage 네임스페이스 |
| `OCI_BUCKET` | `bangpick-dev` |

macOS·Linux에서는 저장소 루트에서 `.env`를 불러온 뒤 백엔드를 실행합니다. 기본 Compose 구성은 OCI 환경변수를 백엔드 컨테이너에 전달하지 않습니다.

```sh
set -a
source ./.env
set +a
cd backend
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

Flyway는 백엔드 시작 시 회원·작업 테이블의 마이그레이션을 적용합니다. 위 실행 예시는 백엔드를 루프백에 바인딩합니다. 다른 환경에서 실제 팀 Worker가 연결할 때는 HTTPS 또는 보호된 터널을 사용합니다. 브라우저 개발 토큰은 로컬 테스트용입니다. Google 로그인을 켜면 브라우저 작업은 회원 ID로 구분되며 이전 개발 토큰으로 생성한 작업은 개발 모드에서 조회할 수 있습니다.

브라우저는 [프론트 실행 안내](../frontend/README.md), 파일 다운로드·결과 업로드는 [Worker 실행 안내](../gpu-worker/README.md), API는 [서비스 간 계약](../contracts/README.md)을 참고하세요.

## 만료 작업 수동 복구

`PROCESSING` 작업의 점유가 만료돼도 GPU 계산이 멈췄다는 뜻은 아닙니다. 운영자는 GPU 호스트에서 이전 실행이 종료됐거나 취소됐음을 확인한 뒤, 해당 작업의 기존 점유 ID로 `recover-expired`를 호출합니다. 점유 ID는 Worker 작업 폴더의 `lease` 파일 또는 운영자가 접근하는 DB에서 확인합니다. 실행 여부가 불명확하면 호출하지 않고 작업을 `PROCESSING`에 둡니다.

이 API는 별도의 `BANGPICK_MEDIA_RECOVERY_TOKEN`이 설정된 경우에만 허용합니다. Worker 토큰으로는 호출할 수 없습니다. 요청에는 `leaseId`, `previousExecutionStopped: true`, `retryable`을 넣습니다. `retryable: true`는 남은 시도가 있을 때 대기열로 돌리고, `false`는 `FAILED`로 마칩니다. [요청 계약과 제한](../contracts/README.md#영상ply-전송)을 확인하세요. 이 확인값은 운영자의 선언이며 백엔드가 GPU 프로세스 종료를 직접 검증하지는 않습니다.
