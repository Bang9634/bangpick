# 개발 환경과 실행 안내

## 현재 구현 상태

| 영역 | 현재 코드에 포함된 기능 |
| --- | --- |
| 프론트엔드 | 영상 전송·작업 조회 함수, Spark·Three.js 기반 PLY 뷰어. 기본 화면은 프로젝트 소개 |
| 백엔드 | 영상·PLY 전송 권한 발급·회수, 객체 크기 확인, MySQL 작업 상태와 결과 키 관리, 대기 작업 순서 선택·점유 API |
| GPU Worker | 지정 작업 점유, 원본 다운로드, 로컬 PLY 업로드와 완료 보고 |
| 로컬 인프라 | 웹·백엔드·MySQL Compose, Worker 별도 Compose와 GPU 할당 설정 |

영상·PLY 전송 API는 기본으로 꺼져 있습니다. 개발 환경에서는 브라우저와 Worker에 서로 다른 개발 토큰을 사용합니다. 설정과 API 형식은 [백엔드 실행 안내](../backend/README.md#oci-파일-전송-개발-환경)와 [서비스 간 계약](../contracts/README.md#영상ply-전송)에 있습니다.

## 필요한 환경과 의존성

| 구성 | 로컬 실행 환경 | 실행 안내 |
| --- | --- | --- |
| 프론트엔드 | React·TypeScript·Vite, Node.js 24 권장 | [프론트 설치·실행](../frontend/README.md) |
| 백엔드 | Java 21·Spring Boot·Maven Wrapper | [백엔드 실행·DB 연결](../backend/README.md) |
| Worker | Python 3.11 이상 | [Worker 전송 명령](../gpu-worker/README.md) |
| 컨테이너 | Docker·Compose v2.30 이상, MySQL 8.4 | [Docker와 GPU 설정](../infra/README.md) |

컨테이너로만 실행할 때 호스트에 Node.js·Java·Python을 모두 설치할 필요는 없습니다. Worker 전송 테스트에는 GPU가 필요하지 않습니다. 실제 학습 환경은 [재구성 실험](experiments/0001-room-reconstruction.md)에 기록합니다.

## 영상·PLY 전송 검증

2026-11-16 개발 환경에서 확인한 내용입니다.

- **팀원 확인:** 브라우저에서 원본 영상을 OCI에 올리고 Worker로 내려받았습니다. Worker로 테스트용 3DGS PLY를 올린 뒤 브라우저에서 내려받아 렌더링했습니다. 이 검증에 사용한 화면은 기본 앱에서 제거했습니다.
- **직접 확인:** 개발 버킷의 객체별 업로드 URL 발급·회수, 로컬 MySQL의 기존 `RESULT_READY` 작업 조회. 백엔드 테스트 6개, Worker 테스트 3개, 프론트 lint·빌드 통과.
- **검증 범위:** 테스트 PLY의 전송과 렌더링. 원룸 모델의 재구성 품질·이동 제한·성능과 운영 배포 결과는 이 기록에 포함하지 않습니다.

초기 Docker·DB 구성의 배경은 [초기 개발 환경 결정](decisions/0005-monorepo-bootstrap.md)에 있습니다. 실제 설정값은 Git에서 제외된 `.env`에 두고 공유할 변수 이름은 [`.env.example`](../.env.example)에서 관리합니다.

2026-11-18 대기열 선택 검증: H2 기반 백엔드 테스트에서 가장 오래된 실행 가능 작업 선택, 미래 `available_at` 제외, 동시 요청의 중복 점유 방지, V2 이전 대기 행의 시각 미상값 보충을 확인했습니다. 현재 Worker CLI는 작업 ID를 직접 받으며 `claim-next`를 아직 호출하지 않습니다. 실제 MySQL 동시성은 검증하지 않았고, GPU 단일 실행 제한·재시도 한도·만료 점유 복구도 아직 구현되지 않았습니다.
