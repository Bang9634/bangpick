# 개발 환경과 작업 안내

기준일: 2026-11-12. 현재 구현 상태와 필요한 환경, 상세 실행 문서의 위치를 관리합니다. 설계는 [시스템 설계](architecture.md), 선택 이유는 [기술 결정](decisions/README.md)을 참고합니다.

## 현재 구현 상태

| 영역 | 현재 코드에 포함된 내용 |
| --- | --- |
| 프론트엔드 | React·TypeScript·Vite 소개용 시작 화면 |
| 백엔드 | Java·Spring Boot·Maven Wrapper, 상태 확인, MySQL 연결 설정 |
| GPU Worker | Python 패키지·실행 진입점. scaffold 상태를 한 번 출력하고 종료 |
| 로컬 인프라 | 웹·백엔드·MySQL Compose, Worker 별도 Compose, GPU 할당 override |

기능 API·매물·인증·지도·3D 뷰어·작업 처리·실제 GPU 학습·OCI 연동·운영 배포·CI는 아직 없습니다. GPU 할당 설정이 있다는 이유로 학습 가능한 이미지로 해석하지 않습니다. 구현이 바뀌면 이 절을 갱신하며 다른 문서에는 요약과 링크만 둡니다.

## 필요한 환경과 의존성

| 개별 개발 대상 | 실행 환경 | 버전·의존성 기준 파일 |
| --- | --- | --- |
| 프론트엔드 | Node.js 24 권장 | `.node-version`, `frontend/package.json`, `frontend/package-lock.json` |
| 백엔드 | Java 21, Maven Wrapper | `backend/pom.xml`, `backend/.mvn/wrapper/maven-wrapper.properties` |
| Worker 초기 패키지 | Python 3.11 기준 | `gpu-worker/.python-version`, `gpu-worker/pyproject.toml` |
| 컨테이너 실행 | 실행 중인 Docker 엔진, Compose v2.30 이상 | `compose.yaml`, `infra/compose.worker*.yaml`, 서비스별 Dockerfile |

컨테이너로만 실행하면 호스트에 Node.js·Java·Python을 모두 설치할 필요는 없습니다. 현재 Worker의 기본 이미지는 GPU 없이 확인할 수 있습니다. 실제 학습 환경은 [재구성 실험](experiments/0001-room-reconstruction.md)과 구분하며 최종 GPU·CUDA·라이브러리 조합은 별도 재현 실험으로 정합니다. 개인 장비 목록을 프로젝트 요구사항으로 대체하지 않습니다.

## 작업별 실행 안내

| 작업 | 상세 명령과 설정을 관리하는 문서 |
| --- | --- |
| 로컬 웹·DB 전체 기동·종료, 포트·환경변수·볼륨 | [인프라 안내](../infra/README.md) |
| 프론트 설치·개발·lint·build | [프론트엔드 안내](../frontend/README.md) |
| 백엔드 검증·기동·DB 연결 | [백엔드 안내](../backend/README.md) |
| Worker 개별 설치·실행 | [Worker 안내](../gpu-worker/README.md) |
| Worker 컨테이너 실행·GPU 할당 | [인프라 안내](../infra/README.md#worker-컨테이너) |
| 그림 재생성 | [설계 시각 자료](diagrams/README.md) |

상세 명령은 위 문서에서 수정하고 이 문서에 복사하지 않습니다. 상태 확인 경로의 계약은 [서비스 간 계약](../contracts/README.md), 코드 검증 기준은 [공통 지침](../AGENTS.md#모노레포와-초기-검증)을 따릅니다.

## 초기 설정 검증 기록


2026-11-12에 다음을 확인했습니다.

- 프론트 `npm run lint`, `npm run build` 통과.
- 백엔드 Maven verify에서 컨텍스트 생성 테스트 1개와 패키징 통과. Docker 빌드 안에서도 기본 테스트 실행.
- frontend·backend·MySQL 이미지 빌드와 Compose 기동 후 세 서비스 healthy 확인.
- 백엔드 직접 접근과 Nginx `/api` 프록시 양쪽에서 전체 health가 UP 응답. 초기 화면 HTML과 번들 참조 확인.
- Worker 로컬 모듈 실행과 Docker 패키지 설치·실행 통과. GPU 할당과 실제 학습은 미검증.
- 실제 .env·개인 .ai-context/·생성 산출물의 Git 제외 확인.

이 기록은 위 날짜의 실행 결과입니다. 현재 컨테이너 가동 상태나 이후 변경의 검증 결과를 대신하지 않습니다.

## 파일과 비밀정보

실제 값은 Git에서 제외된 `.env`나 실행 환경에 두고 `.env.example`에는 안전한 예시만 기록합니다. 원본·모델·체크포인트는 Git에 추가하지 않습니다. 로컬 데이터 경로를 사용할 때 `.gitignore`의 제외 여부를 확인합니다. 데이터 식별자·재현 명령·측정값은 [실험 기록](experiments/README.md)에 남기며 공개 문서에는 개인 장비 목록·로컬 절대 경로·비밀정보를 넣지 않습니다.
