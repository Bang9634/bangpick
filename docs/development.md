# 개발 환경과 실행 안내

기준일: 2026-11-12. 이 문서는 프로젝트 실행에 필요한 환경 조건과 방법을 다룹니다. 개인 장비 목록은 관리하지 않습니다.

## 저장소 상태

모노레포 초기 프로젝트를 생성했습니다. frontend는 React·TypeScript·Vite 시작 화면, backend는 Java 21·Spring Boot 4.1.2·Maven Wrapper와 상태 확인·MySQL 연결 설정, gpu-worker는 Python 실행 진입점을 포함합니다. 기능 API·매물·인증·지도·3D 뷰어·학습 처리는 아직 없습니다. Docker 로컬 실행 구성은 있지만 운영 배포·CI·OCI 연동은 구현하지 않았습니다.

프론트 의존성은 frontend/package-lock.json, 백엔드는 backend/pom.xml과 Maven Wrapper, Worker 패키지는 gpu-worker/pyproject.toml로 관리합니다. 루트 .env.example은 현재 사용하는 로컬 DB·포트 변수만 담습니다. 실제 값은 Git에서 제외된 .env 또는 실행 환경에 둡니다.

## 초기 프로젝트 실행

루트에서 다음 명령을 실행합니다. Docker Compose v2.30 이상과 실행 중인 Docker 엔진이 필요합니다. 기본 이미지의 GPU 없는 실행은 로컬 웹 개발용입니다.

```sh
cp .env.example .env
# .env의 두 비밀번호를 변경
docker compose up --build -d
docker compose ps
```

웹은 `http://localhost:8088`, 백엔드 전체 상태는 `http://localhost:8080/api/actuator/health`입니다. 웹 서버의 `/api` 프록시도 같은 경로를 전달합니다. 이 초기 구성은 localhost에서만 접근할 수 있으며 운영 공개 배포 설정이 아닙니다. MySQL은 이름 있는 볼륨으로 보존하며 호스트 기본 접속 포트는 13306입니다. 컨테이너 내부는 3306을 사용합니다.

프론트 개별 개발은 frontend에서 `npm ci`, `npm run dev`로 실행하고 `npm run lint`, `npm run build`로 확인합니다. 권장 Node.js는 24이며 `.node-version`에 기록했습니다. Vite 개발 서버는 5173, 백엔드는 기본 8080을 사용합니다. 루트 .env의 포트와 다르게 실행하면 Vite 프록시 대상도 조정합니다.

백엔드 개별 개발은 backend에서 `./mvnw verify`, `./mvnw spring-boot:run`을 사용합니다. Java 21이 필요합니다. DB 연결은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 셸 또는 IDE 환경에 설정합니다. Maven/JVM은 루트 .env를 자동으로 읽지 않습니다. DB 접속은 전체 health로 별도 확인하며 컨텍스트 생성 테스트만으로 접속 성공을 단정하지 않습니다.

Worker는 `docker compose -f infra/compose.worker.yaml run --build --rm worker`로 확인합니다. 한 번 scaffold 상태를 출력하고 종료하며 대기 작업을 처리하는 상시 서비스는 아닙니다. GPU 할당 override와 실행 조건은 [인프라 안내](../infra/README.md)를 따릅니다. CUDA·PyTorch·COLMAP·Nerfstudio는 아직 설치하지 않았으므로 학습 가능한 이미지라고 해석하지 않습니다.

| 변수 | 용도 |
| --- | --- |
| DB_NAME | Compose가 생성할 로컬 DB 이름 |
| DB_USERNAME | 로컬 앱용 DB 사용자 |
| DB_PASSWORD | 앱용 DB 비밀번호; 실제 값은 공유하지 않음 |
| MYSQL_ROOT_PASSWORD | DB 초기화용 관리자 비밀번호; 실제 값은 공유하지 않음 |
| WEB_PORT / API_PORT / DB_PORT | 호스트의 localhost 바인딩 포트 |
| DB_URL | 개별 백엔드의 JDBC URL; Compose에서는 서비스명 db 기준으로 설정 |

실행·종료·볼륨·비밀번호 변경 주의사항은 [infra/README.md](../infra/README.md)를 참고합니다. 특정 개인 장비의 사양이나 설치 목록은 개발 요구사항으로 기록하지 않습니다.

## 초기 설정 검증 기록

2026-11-12에 다음을 확인했습니다.

- 프론트 `npm run lint`, `npm run build` 통과.
- 백엔드 Maven verify에서 컨텍스트 생성 테스트 1개와 패키징 통과. Docker 빌드 안에서도 기본 테스트 실행.
- frontend·backend·MySQL 이미지 빌드와 Compose 기동 후 세 서비스 healthy 확인.
- 백엔드 직접 접근과 Nginx `/api` 프록시 양쪽에서 전체 health가 UP 응답. 초기 화면 HTML과 번들 참조 확인.
- Worker 로컬 모듈 실행과 Docker 패키지 설치·실행 통과. GPU 할당과 실제 학습은 미검증.
- 실제 .env·개인 .ai-context/·생성 산출물의 Git 제외 확인.

## 환경별 책임

| 환경 | 역할 |
| --- | --- |
| 웹 개발 환경 | 프론트엔드·백엔드 개발 및 검증 |
| GPU 학습 환경 | 카메라 추정·3DGS 학습·내보내기 |
| 사용자 브라우저 | 모델 로딩·렌더링·입력·이동 제한 |
| 배포 환경 | 웹·API·모델 파일 제공 |

실험 재현에 필요한 GPU·라이브러리 버전 등은 [실험 기록](experiments/0001-room-reconstruction.md)에 한정해 기록합니다. 이 조합이 프로젝트의 최종 표준 환경으로 채택된 것은 아닙니다.

웹 개발 장비와 GPU 학습 장비가 같을 필요는 없습니다. 설치법과 버전 호환성은 실제 설치 작업 시 공식 문서로 확인하고, 실행 가능한 프로젝트 요구사항을 기록합니다. 특정 개발자의 현재 설치 패키지 목록을 요구사항으로 대체하지 않습니다.

## 학습 장비 운영 방향

2026-11-12 사용자 확인: 초기에는 팀의 GPU PC를 켜 두고 필요할 때 사용할 수 있으며, 추후 클라우드 등 다른 GPU 환경을 확보할 예정입니다. 현재 서비스 연동이 구현된 상태나 클라우드 공급자가 정해진 상태를 의미하지는 않습니다.

이에 따른 설계 제안은 다음과 같습니다.

- 웹서비스는 GPU PC의 상시 가동에 의존하지 않습니다. 공개 모델·뷰어·충돌 데이터는 GPU PC 밖의 웹 제공 저장소에 보관합니다.
- Worker가 없을 때 새 생성 요청은 대기하고 기존 매물·투어 조회는 계속 제공합니다. 장비에 접속하지 못한 것을 재구성 실패로 표시하지 않습니다.
- Worker가 활성화되면 인증된 내부 API에서 작업을 가져오는 구조를 우선 검토합니다. 팀 PC에 외부 요청 수신용 서버를 반드시 열 필요는 없습니다.
- 작업 ID·입력 자산 ID·처리 설정·결과 자산 ID를 사용하고 개인 PC의 절대 경로를 작업 계약에 넣지 않습니다. 클라우드로 옮길 때 같은 계약을 유지합니다.
- 실행 중 전원·연결이 끊기면 작업 점유 만료와 복구 정책으로 처리합니다. 자동 재실행과 체크포인트 이어 학습은 별개이므로 지원 여부를 확인합니다. 초기에는 Worker 하나에서 한 작업씩 실행하는 방안을 제안합니다.

## 채택한 저장·작업 처리 흐름

[결정 0003](decisions/0003-storage-and-worker-flow.md)에 따라 백엔드는 권한·자산 정보·작업 상태, 저장소는 원본·결과 파일, Worker는 비동기 재구성을 담당합니다. 서비스 구성은 업로드 허가 → 저장소 업로드 → 완료 확인 → 작업 등록 → Worker 처리 → 결과 저장·보고 → 검수·공개 → 뷰어 조회 흐름입니다.

서비스 통합 개발은 처음부터 오브젝트 스토리지를 사용하고 폴더 기반 업로드 중계 기능을 별도로 먼저 개발하지 않습니다. 로컬 폴더는 Worker의 임시 다운로드·전처리·학습 공간과 개별 테스트에 사용할 수 있습니다. 보존이 필요한 로컬 파일은 Docker 컨테이너의 임시 쓰기 계층에만 두지 않고 volume 또는 bind mount 등으로 관리합니다.

오브젝트 스토리지는 버킷과 키로 파일을 관리하며 API·URL을 통해 접근합니다. DB에는 영구 자산 ID와 저장 키를 기록하고, 서명 URL은 요청 시 권한에 맞게 발급합니다. 브라우저 직접 업로드와 GPU 처리는 별개입니다. 공급자 선택 시 팀 계정·크레딧, 저장·요청·전송 비용, 서명 업로드·CORS·분할 업로드 등 필요한 기능을 확인합니다. 현재 버킷이나 저장소 연동은 구현하지 않았습니다.

## Oracle Object Storage 우선 후보 검토

2026-11-12 사용자 제안에 따라 OCI Object Storage를 우선 후보로 검토합니다. 실제 계정·버킷·브라우저 업로드 검증 전이며 공급자 최종 선정이나 무료 사용 보장 기록이 아닙니다.

OCI Java·Python SDK와 만료·대상·읽기/쓰기 범위를 지정하는 Pre-Authenticated Request(PAR)를 사용할 수 있습니다. 초기 연동은 OCI SDK와 특정 객체에 제한된 단기 업로드 PAR을 사용하는 방안을 제안합니다. PAR URL은 가진 사람이 접근할 수 있으므로 영구 공개 설정이나 브라우저에 계정 인증키를 전달하는 방식과 혼동하지 않습니다. S3 호환 API도 있으나 전체 기능 동등성은 보장되지 않습니다.

공식 Always Free 상세 문서 기준, 체험 종료 후 Always Free 전용 계정은 저장 계층 합산 20GB와 월 50,000회 Object Storage API 요청을 안내합니다. 유료/체험 크레딧 계정에는 Standard·Infrequent Access·Archive 각각 10GB를 안내하며 체험 설명이 별도로 있으므로 실제 계정·리전의 무료 적용 범위를 콘솔에서 확인합니다. 체험 종료 시 무료 용량을 넘긴 경우 객체 삭제 가능성도 공식 문서에서 확인해야 합니다. 무료 용량을 채우려고 실시간 조회용 파일을 Archive에 배치하지 않습니다.

브라우저 접근 시 OCI 공식 FAQ는 CORS 응답 헤더가 고정되어 수정할 수 없다고 안내합니다. AWS의 버킷 CORS 설정을 그대로 전제하지 않고 직접 업로드·뷰어 GET·필요한 요청 헤더·분할 업로드 경로를 실제로 확인합니다. 원본과 공개 결과는 비공개 버킷 및 제한된 접근 권한을 기본으로 검토하고, 공개 투어 제공 방식은 별도로 검증합니다.

일반 매물 원본은 [검수 승인 후 7일 보관·삭제 정책](decisions/0004-source-video-retention.md)을 따릅니다. 삭제 기한은 백엔드의 검수 승인 시각을 기준으로 계산하며 객체 업로드 시각 기준의 수명주기 규칙으로 대체하지 않습니다. 최종 웹 모델은 유지하고 프레임·체크포인트 등 중간 산출물의 보관 범위는 별도로 정합니다. 무료 한도는 버킷마다 독립된 용량으로 계산하지 않습니다.

공식 근거 (2026-11-12 확인): [Always Free 상세 한도](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm), [PAR](https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/usingpreauthenticatedrequests.htm), [OCI SDK](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/sdks.htm), [CORS·S3 호환성 FAQ](https://www.oracle.com/cloud/storage/object-storage/faq/).

## 현재 초기 구성과 후속 처리 설계

2026-11-12 프로젝트 생성 기준입니다. React·TypeScript·Vite와 Java·Spring Boot·Maven으로 초기 프로젝트를 생성했습니다. Python Worker는 패키지 진입점만 구성했으며 아래 실제 작업 처리·학습 도구 연동은 후속 설계입니다. 채택된 처리 구조는 위 결정 0003을 따릅니다.

- React + TypeScript + Vite: 브라우저 UI와 지도·3D 뷰어 통합. 라우팅과 데이터 조회 정책은 별도로 구성합니다.
- Java + Spring Boot: 매물·회원·자산 메타데이터, 재구성 작업 생성·상태·접근 권한 관리.
- MySQL: 서비스 정보와 작업 상태 저장. 대형 모델·영상 파일은 별도 저장소에 두고 식별자만 연결합니다.
- Python GPU Worker: FFmpeg·COLMAP·Nerfstudio/gsplat 실행을 조정하고 로그·산출물·실패를 관리합니다. 네이티브 도구를 Python으로 다시 작성하지 않습니다.
- 초기 작업 전달: 백엔드의 내부 API를 통해 Worker가 대기 작업을 가져오고 진행·완료를 보고하는 구조를 검토합니다. DB 기반 작업 관리로 시작하고, 작업 점유·타임아웃·재시도·중복 실행 방지를 설계합니다. FastAPI·Redis·브로커는 필수로 도입하지 않습니다.
- 시각 결과는 저장소에서 사용자 브라우저로 제공하고 브라우저가 렌더링합니다. 학습 서버의 실시간 화면 스트리밍을 기본 구성으로 가정하지 않습니다.

웹 백엔드와 GPU Worker는 별도 프로세스·컨테이너로 분리할 수 있으며 반드시 다른 물리 장비여야 하는 것은 아닙니다. 한 장비를 쓸 때에는 CPU·RAM·디스크·GPU 자원 경합을 확인합니다. 서로 다른 장비라면 파일 전달과 인증된 내부 통신 경로를 별도로 마련합니다. 학습 환경 권장은 Linux + NVIDIA GPU + 호환 CUDA 환경이며, Docker를 쓸 때도 호스트 드라이버와 NVIDIA Container Toolkit 설정이 필요합니다.

공식 참고 자료 (2026-11-12 확인): [React 구성](https://react.dev/learn/build-a-react-app-from-scratch), [Spring Boot](https://spring.io/projects/spring-boot/), [Splatfacto](https://docs.nerf.studio/nerfology/methods/splat.html), [GPU 컨테이너](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html), [NAVER Maps](https://guide.ncloud-docs.com/docs/maps-overview).

## 파일과 비밀정보

- 원본·학습 데이터·산출물은 루트의 `data/`, `datasets/`, `uploads/`, `outputs/`, `exports/`, `checkpoints/` 등 Git 제외 경로 또는 별도 저장소에 둡니다. 중첩 경로는 별도로 제외 여부를 확인합니다.
- 추적 가능한 작은 실험 설정·명령·측정 결과는 `docs/experiments/`에 둡니다. 모델·영상 자체를 문서 폴더에 넣지 않습니다.
- 환경변수 실제 값은 로컬 환경 또는 배포 시스템의 비밀정보 관리 기능에 둡니다. 공통 문서와 환경 예제에도 넣지 않습니다.
- `.env.example`과 `.env.*.example`은 공유할 수 있습니다. 실제 값이 없는지 별도로 확인합니다.
- 공개 문서에는 실행·재현에 필요한 정보만 기록합니다. 개인 장비의 모델·전체 사양·설치 목록, 사용자명·홈 디렉토리·절대 경로·장비 식별자는 기록하지 않습니다. 성능 비교에 필요한 하드웨어 정보는 개인과 연결하지 않고 실험 조건으로만 기재합니다.

## 개발 환경 확정 시 보완할 내용

GPU 학습 환경·계약·OCI 연동·모델 제공 경로·운영 배포는 해당 구현 시 실제 검증한 설정을 추가합니다. 데이터 공개 동의와 프로젝트 라이선스도 배포 전에 정합니다.
