package com.spendanalyzer.testsupport;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Wipes and re-creates the test database when the test application context starts. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfig {

	@Bean
	public FlywayMigrationStrategy cleanThenMigrate() {
		return flyway -> {
			flyway.clean();
			flyway.migrate();
		};
	}

}
