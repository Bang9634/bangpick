# bangpick 백엔드

Java 21과 Maven Wrapper로 실행합니다. Windows에서는 `mvnw.cmd`를 사용하세요. 전체 구성은 [개발 안내](../docs/development.md)에 있습니다.

```sh
./mvnw verify
./mvnw spring-boot:run
```

개별 실행 시 기본 DB는 `localhost:13306/bangpick`입니다. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`는 셸·IDE 환경에서 지정하세요. JVM은 저장소 루트의 `.env`를 자동으로 읽지 않습니다. DB는 [인프라 안내](../infra/README.md)대로 기동하며, 상태는 `http://127.0.0.1:8080/api/actuator/health`에서 확인합니다.

## OCI 파일 전송 개발 환경

1. 저장소 루트에서 `docker compose up -d db`로 로컬 MySQL을 실행합니다. 기존 DB를 사용할 경우 아래의 DB 변수만 바꿉니다.
2. OCI 설정 파일에 API 서명 키를 구성하고 로컬 백엔드 사용자만 읽을 수 있게 합니다. 키와 설정을 Git에 추가하지 않습니다. 백엔드의 IAM 권한은 개발 버킷으로 제한하고, 객체 메타데이터 확인과 읽기·쓰기 PAR 생성 및 회수를 허용합니다. [OCI SDK 설정 파일](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/sdkconfig.htm) · [PAR 필요 권한](https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/usingpreauthenticatedrequests.htm#required-permissions)
3. [`.env.example`](../.env.example)의 OCI 항목을 로컬 `.env`에 채웁니다. 이미 `.env`가 있다면 필요한 항목만 추가합니다. 아래의 값을 준비한 뒤 전송 기능을 사용할 때만 `BANGPICK_MEDIA_ENABLED=true`로 바꿉니다.

| 변수 | 개발 예시 |
| --- | --- |
| `BANGPICK_MEDIA_ENABLED` | `true` — 기본값은 `false` |
| `BANGPICK_MEDIA_DEV_TOKEN` | 브라우저에서 입력하는 개발 토큰. `openssl rand -hex 32`로 생성 |
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

기능이 켜지면 Flyway가 `media_jobs` 테이블을 생성합니다. 위 실행 예시는 백엔드를 루프백에 바인딩합니다. 다른 환경에서 실제 팀 Worker가 연결할 때는 HTTPS 또는 보호된 터널을 사용합니다. 개발 토큰은 단일 개발 소유자 식별용이므로 이 기능을 현재 상태로 인터넷에 공개하지 않습니다.

브라우저는 [프론트 실행 안내](../frontend/README.md), 파일 다운로드·결과 업로드는 [Worker 실행 안내](../gpu-worker/README.md), API는 [서비스 간 계약](../contracts/README.md)을 참고하세요.
