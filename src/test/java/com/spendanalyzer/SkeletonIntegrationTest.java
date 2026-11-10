package com.spendanalyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;

import com.spendanalyzer.config.SecurityConfig;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SkeletonIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void flywayCreatedTheSchema() {
		Integer applied = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success").query(Integer.class).single();
		assertThat(applied).isGreaterThanOrEqualTo(1);
	}

	@Test
	void categoryEnumMatchesCategoryTable() {
		List<String> inDb = jdbc.sql("SELECT code FROM categories ORDER BY sort_order").query(String.class).list();
		List<String> inCode = Arrays.stream(Category.values()).map(Enum::name).toList();
		assertThat(inDb).containsExactlyElementsOf(inCode);
		for (Category c : Category.values()) {
			String label = jdbc.sql("SELECT label FROM categories WHERE code = ?").param(c.name()).query(String.class).single();
			String need = jdbc.sql("SELECT need_flag FROM categories WHERE code = ?").param(c.name()).query(String.class).single();
			assertThat(label).isEqualTo(c.label());
			assertThat(need).isEqualTo(c.needFlag().name());
		}
	}

	@Test
	void healthIsPublicAndCheap() throws Exception {
		mvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(content().string("ok"));
	}

	@Test
	void homePageIsPublicAndCarriesSecurityHeaders() throws Exception {
		mvc.perform(get("/index.html"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Referrer-Policy", "no-referrer"));
	}

	@Test
	void apiNeedsLogin() throws Exception {
		mvc.perform(get("/api/meta"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("unauthorized"));
	}

	@Test
	void apiWorksWhenLoggedIn() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		mvc.perform(get("/api/meta").with(TestData.as(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.categories.length()").value(Category.values().length))
			.andExpect(jsonPath("$.paymentModes[0].code").value("UPI"));
	}

	@Test
	void unknownPathsAreRefused() throws Exception {
		mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
		mvc.perform(get("/some-other-page")).andExpect(status().isUnauthorized());
	}

	@Test
	void crossSitePostIsRejected() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		mvc.perform(post("/api/entries").with(TestData.as(user))
			.header("Origin", "https://evil.example")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("cross_site"));
		mvc.perform(post("/api/entries").with(TestData.as(user))
			.header("Sec-Fetch-Site", "cross-site")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isForbidden());
	}

	@Test
	void formPostIsRejected() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		mvc.perform(post("/api/entries").with(TestData.as(user))
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.content("a=b")).andExpect(status().isUnsupportedMediaType());
	}

}
