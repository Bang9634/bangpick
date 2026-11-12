# 0005 — 모노레포 초기 프로젝트와 독립 Docker 구성

- 상태: 채택·초기 생성 완료
- 날짜: 2026-11-12
- 근거: 사용자가 제안된 디렉토리 구조·프로젝트 생성·Docker 설정을 요청하고 React·TypeScript·Vite를 선택함. Maven 경험을 반영해 백엔드 빌드 도구는 Maven을 사용함.

## 구성

frontend/, backend/, gpu-worker/, contracts/, infra/, docs/, .github/를 한 저장소에서 관리합니다. 프론트·백엔드·Worker는 의존성·Docker 이미지·실행 진입점을 각각 가집니다. GPU 서버가 별도여도 저장소는 분리하지 않습니다.

- 프론트: 공식 Vite React·TypeScript 템플릿, Node.js 24 권장, package-lock.json 공유.
- 백엔드: 공식 Spring Initializr, Java 21, Spring Boot 4.1.2, Maven Wrapper. Web MVC·Actuator·JDBC·MySQL 드라이버 구성.
- 로컬 DB: MySQL 8.4 컨테이너와 이름 있는 데이터 볼륨. 기본 호스트 포트 13306, 내부 포트 3306.
- Worker: Python 3.11 기준 패키지·한 번 실행 후 종료하는 초기 진입점. 기본 이미지는 GPU 없이 확인 가능.
- 웹 Compose와 Worker Compose는 분리. GPU 할당 override는 NVIDIA 호스트용으로 제공하며 학습 라이브러리를 설치했다는 뜻은 아님.

## 범위와 이유

현재는 프로젝트 생성까지만 진행합니다. 매물·인증·작업 소비·OCI·지도·뷰어·3DGS 학습 로직을 구현하지 않습니다. 초기 Worker의 Python 환경은 팀원의 기존 학습 환경을 교체한 결정이 아닙니다. 실제 학습 도구를 실행하는 환경은 재현 실험 기준으로 별도 선정합니다.

Maven과 Gradle은 Spring Boot에서 모두 사용 가능하지만 현재 규모에서는 팀의 익숙함을 우선합니다. 모노레포를 위해 Gradle·Node 워크스페이스·별도 빌드 오케스트레이터를 도입할 필요는 없습니다.

공식 출처 (2026-11-12 확인): [Vite 시작](https://vite.dev/guide/), [Spring Boot 요구사항](https://docs.spring.io/spring-boot/system-requirements.html), [Maven 생명주기](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html).
