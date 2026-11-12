# 백엔드

Java 21 + Spring Boot + Maven Wrapper 백엔드 프로젝트입니다. 의존성은 `pom.xml`, 현재 구현 범위는 [개발 안내](../docs/development.md#현재-구현-상태)를 참고합니다.

```sh
./mvnw verify
./mvnw spring-boot:run
```

이 디렉토리에서 실행합니다. Windows는 `mvnw.cmd`를 사용합니다. DB 실행과 포트·환경변수는 [인프라 안내](../infra/README.md)를 참고합니다. 실행하려면 MySQL을 시작하고 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 해당 셸·IDE에 설정합니다. 루트 `.env`는 Docker Compose가 읽으며 Maven이나 JVM이 자동으로 읽지 않습니다. 기본 JDBC URL은 `src/main/resources/application.properties`를 따릅니다.

상태 확인은 `http://localhost:8080/api/actuator/health`이며 DB 연결을 포함합니다. 외부에 상세 정보를 노출하지 않습니다. 컨텍스트 생성 테스트 통과와 실제 DB 연결 성공은 별도로 확인합니다.
