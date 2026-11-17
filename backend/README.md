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

## OCI 파일 전송 개발 환경

1. 저장소 루트에서 `docker compose up -d db`로 로컬 MySQL을 실행합니다. 기존 DB를 사용할 경우 아래의 DB 변수만 바꿉니다.
2. OCI 설정 파일에 API 서명 키를 구성하고 로컬 백엔드 사용자만 읽을 수 있게 합니다. 키와 설정을 Git에 추가하지 않습니다. 백엔드의 IAM 권한은 개발 버킷으로 제한하고, 객체 메타데이터 확인과 읽기·쓰기 PAR 생성 및 회수를 허용합니다. [OCI SDK 설정 파일](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/sdkconfig.htm) · [PAR 필요 권한](https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/usingpreauthenticatedrequests.htm#required-permissions)
3. [`.env.example`](../.env.example)의 OCI 항목을 로컬 `.env`에 채웁니다. 이미 `.env`가 있다면 필요한 항목만 추가합니다. 아래의 값을 준비한 뒤 전송 기능을 사용할 때만 `BANGPICK_MEDIA_ENABLED=true`로 바꿉니다.

| 변수 | 개발 예시 |
| --- | --- |
| `BANGPICK_MEDIA_ENABLED` | `true` — 기본값은 `false` |
| `BANGPICK_MEDIA_DEV_TOKEN` | Google 로그인이 꺼진 로컬 환경에서 사용하는 브라우저 개발 토큰. `openssl rand -hex 32`로 생성 |
| `BANGPICK_MEDIA_WORKER_TOKEN` | Worker용 토큰. 위와 별도로 한 번 더 생성 |
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
