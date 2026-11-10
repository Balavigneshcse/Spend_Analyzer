package com.spendanalyzer.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The session filter on its own: no application, no database. */
@ExtendWith(OutputCaptureExtension.class)
class SessionAuthFilterTest {

	private static final String TOKEN = "Zm9yLXRoZS10ZXN0LW9ubHktbm90LWEtcmVhbC10b2tlbg";

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	private final MockFilterChain chain = new MockFilterChain();

	private static SessionAuthFilter filterWith(SessionAuthenticator authenticator) {
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		beans.registerSingleton("sessionAuthenticator", authenticator);
		return new SessionAuthFilter(beans.getBeanProvider(SessionAuthenticator.class));
	}

	private static MockHttpServletRequest apiRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/balances");
		request.setCookies(new Cookie("sa_session", TOKEN));
		return request;
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void aFailingSessionLookupIsAnsweredInTheApiErrorShape(CapturedOutput output) throws Exception {
		SessionAuthFilter filter = filterWith(request -> {
			throw new IllegalStateException("the database cannot be reached");
		});

		filter.doFilter(apiRequest(), response, chain);

		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getContentAsString())
			.isEqualTo("{\"error\":\"server_error\",\"message\":\"Something went wrong. Please try again.\"}");
		// the request goes no further and nobody is logged in
		assertThat(chain.getRequest()).isNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		// it is logged, without the session cookie
		assertThat(output.getAll()).contains("the database cannot be reached").doesNotContain(TOKEN);
	}

	@Test
	void aValidSessionLogsTheUserInAndTheRequestGoesOn() throws Exception {
		AuthUser user = new AuthUser(UUID.randomUUID(), "student@example.com", "Student");
		SessionAuthFilter filter = filterWith(request -> Optional.of(user));
		MockHttpServletRequest request = apiRequest();
		Authentication[] seenByTheChain = new Authentication[1];

		filter.doFilter(request, response, (req, res) -> {
			seenByTheChain[0] = SecurityContextHolder.getContext().getAuthentication();
		});

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(seenByTheChain[0]).isNotNull();
		assertThat(seenByTheChain[0].getPrincipal()).isEqualTo(user);
	}

	@Test
	void aRequestWithoutASessionGoesOnAnonymously() throws Exception {
		SessionAuthFilter filter = filterWith(request -> Optional.empty());
		MockHttpServletRequest request = apiRequest();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

}
