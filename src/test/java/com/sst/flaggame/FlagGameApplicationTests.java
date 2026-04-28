package com.sst.flaggame;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.task.scheduling.enabled=false",
		"spring.security.oauth2.client.registration.github.client-id=test-client",
		"spring.security.oauth2.client.registration.github.client-secret=test-secret",
		"spring.datasource.url=jdbc:mysql://localhost:3306/flaggame?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true",
		"spring.datasource.username=flaggame",
		"spring.datasource.password=flaggame_pw"
})
class FlagGameApplicationTests {

	@Test
	void contextLoads() {
	}

}
