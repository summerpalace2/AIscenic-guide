package com.ai.guide;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ScenicGuideApplicationTests {

	@Autowired
	private DataSource dataSource;

	@Test
	void contextLoads() throws Exception {
		try (var connection = dataSource.getConnection()) {
			String url = connection.getMetaData().getURL();
			assertTrue(url.startsWith("jdbc:sqlite:"));
			Path database = Path.of(url.substring("jdbc:sqlite:".length())).toAbsolutePath().normalize();
			assertTrue(database.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()),
					"Spring tests must not use the developer database");
			assertTrue(database.getFileName().toString().startsWith("scenic-guide-tests-"));
		}
	}

}
