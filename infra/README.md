# 인프라 초기 설정

루트 `compose.yaml`은 로컬에서 frontend·backend·MySQL을 실행합니다. 외부 공개·TLS·Oracle 버킷·운영 배포 자동화는 포함하지 않습니다. 로컬 포트는 루프백에만 바인딩합니다.

필요한 Docker 환경은 [개발 안내](../docs/development.md#필요한-환경과-의존성)를 참고합니다. 이 문서는 Compose 명령·포트·환경변수·볼륨 설정을 담당합니다. 장비 배치와 처리 책임은 [시스템 설계](../docs/architecture.md)에 있습니다.

## 로컬 웹·DB

처음 환경을 준비할 때만 예제를 복사하고 두 비밀번호를 변경합니다. 기존 `.env`를 덮어쓰지 않습니다.

```sh
cp .env.example .env
```

환경을 준비한 뒤 실행합니다.

```sh
docker compose up --build -d
docker compose ps
docker compose down
```

명령은 저장소 루트에서 실행합니다. `docker compose down`은 DB 볼륨을 유지합니다. `down -v`는 DB 데이터를 삭제하므로 초기화가 필요한 경우에만 사용합니다. 비밀번호는 최초 DB 초기화에 사용되므로 기존 볼륨이 있는 상태에서 .env만 바꾼다고 DB 비밀번호가 변경되지는 않습니다.

| 대상 | 기본 접근 위치 |
| --- | --- |
| 웹 | `http://localhost:8088` |
| 백엔드 전체 상태 | `http://localhost:8080/api/actuator/health` |
| 웹 서버의 API 프록시 | `http://localhost:8088/api/actuator/health` |
| MySQL | `127.0.0.1:13306`, 컨테이너 내부 포트는 3306 |

호스트 포트는 `.env`로 변경할 수 있습니다. 이 구성은 로컬 개발용이며 외부 공개 배포 설정이 아닙니다.

## 환경변수

루트 `.env.example`은 공유 예시, `.env`는 Git에서 제외된 실제 로컬 값입니다. Compose는 루트 `.env`를 읽지만 개별 Maven/JVM 실행이 자동으로 읽지는 않습니다.

| 변수 | 적용 범위·용도 |
| --- | --- |
| DB_NAME | Compose가 생성할 로컬 DB 이름 |
| DB_USERNAME | Compose의 앱용 DB 사용자·백엔드 연결 사용자 |
| DB_PASSWORD | 앱용 DB 비밀번호. 실제 값은 공유하지 않음 |
| MYSQL_ROOT_PASSWORD | 최초 DB 초기화용 관리자 비밀번호 |
| WEB_PORT / API_PORT / DB_PORT | 호스트의 localhost 바인딩 포트 |
| DB_URL | 백엔드 개별 실행용 JDBC URL. Compose에서는 서비스명 `db` 기준으로 구성 |

실제 적용값과 기본값은 `compose.yaml`·`.env.example`·백엔드 `application.properties`를 확인합니다. Vite 개발 서버를 쓰면서 API 포트를 바꾸면 프론트 프록시 대상도 조정해야 합니다. 개별 실행 방법은 [프론트엔드](../frontend/README.md)·[백엔드](../backend/README.md) 안내를 참고합니다.

## Worker 컨테이너

Worker 초기 패키지는 GPU 없이 단독 확인할 수 있습니다. 한 번 상태를 출력하고 종료하며 작업을 대기·학습하는 서비스가 아닙니다.

```sh
docker compose -f infra/compose.worker.yaml run --build --rm worker
```

Linux NVIDIA GPU 장비에서 GPU 할당 설정을 추가하는 명령은 다음과 같습니다.

```sh
docker compose -f infra/compose.worker.yaml -f infra/compose.worker.gpu.yaml run --build --rm worker
```

GPU override는 GPU 접근 설정만 추가합니다. NVIDIA 호스트 드라이버·Container Toolkit이 필요하며, 이미지에는 CUDA·PyTorch·COLMAP·Nerfstudio가 아직 없습니다. 실제 학습 환경은 팀의 재현 실험을 기준으로 별도 이미지와 버전을 정합니다. Worker의 작업 볼륨은 임시 작업 공간이며 공통 오브젝트 스토리지를 대체하지 않습니다.

현재 `worker-work` 볼륨을 `/work`에 연결합니다. 보존해야 하는 작업 파일은 컨테이너의 임시 쓰기 계층에만 두지 않습니다. 로컬 작업 공간의 정리와 원본 영상의 서버 보관 정책은 별개입니다.
