package com.spendanalyzer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

	@Test
	void renderStyleWithoutPort() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://app_user:s3cret@dpg-abc123-a/spend_db");
		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-abc123-a:5432/spend_db");
		assertThat(url.username()).isEqualTo("app_user");
		assertThat(url.password()).isEqualTo("s3cret");
	}

	@Test
	void neonStyleKeepsOnlySslMode() {
		DatabaseUrl url = DatabaseUrl.parse(
				"postgresql://neondb_owner:npg_AbC123@ep-cool-name-a1b2c3.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");
		assertThat(url.jdbcUrl())
			.isEqualTo("jdbc:postgresql://ep-cool-name-a1b2c3.ap-southeast-1.aws.neon.tech:5432/neondb?sslmode=require");
		assertThat(url.username()).isEqualTo("neondb_owner");
		assertThat(url.password()).isEqualTo("npg_AbC123");
	}

	@Test
	void postgresSchemeWithPortAndEncodedPassword() {
		DatabaseUrl url = DatabaseUrl.parse("postgres://u:p%40ss%2Fw+rd%3A1@localhost:5433/db");
		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5433/db");
		assertThat(url.password()).isEqualTo("p@ss/w+rd:1");
	}

	@Test
	void jdbcUrlPassesThrough() {
		DatabaseUrl url = DatabaseUrl.parse("jdbc:postgresql://localhost:5432/x");
		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/x");
		assertThat(url.username()).isNull();
	}

	@Test
	void rejectsRubbishWithoutLeakingIt() {
		assertThatThrownBy(() -> DatabaseUrl.parse("mysql://u:topsecret@h/db")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining("topsecret");
		assertThatThrownBy(() -> DatabaseUrl.parse("postgresql://u:p@host/")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DatabaseUrl.parse("  ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void toStringHidesPassword() {
		assertThat(DatabaseUrl.parse("postgresql://u:topsecret@h/db").toString()).doesNotContain("topsecret");
	}

}
