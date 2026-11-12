# 백엔드

Java 21 + Spring Boot + Maven Wrapper로 생성했습니다. Web MVC·Actuator·JDBC·MySQL 드라이버만 구성하며 기능 API·도메인·테이블은 아직 없습니다.

```sh
./mvnw test
./mvnw package
./mvnw spring-boot:run
```

이 디렉토리에서 실행합니다. Windows는 `mvnw.cmd`를 사용합니다. 로컬 MySQL 기본 포트는 13306입니다. 실행하려면 MySQL을 시작하고 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 해당 셸·IDE에 설정합니다. 루트 `.env`는 Docker Compose가 읽으며 Maven이나 JVM이 자동으로 읽지 않습니다.

상태 확인은 `http://localhost:8080/api/actuator/health`이며 DB 연결을 포함합니다. 외부에 상세 정보를 노출하지 않습니다. 컨텍스트 생성 테스트 통과와 실제 DB 연결 성공은 별도로 확인합니다.
