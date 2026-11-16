package io.bangpick.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"bangpick.media.enabled=false", "spring.flyway.enabled=false",
		"spring.datasource.url=jdbc:h2:mem:bootstrap",
		"spring.datasource.username=sa", "spring.datasource.password="
})
class BangpickBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
