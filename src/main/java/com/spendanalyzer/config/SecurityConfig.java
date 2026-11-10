package com.spendanalyzer.config;

import com.spendanalyzer.security.SameOriginApiFilter;
import com.spendanalyzer.security.SessionAuthFilter;
import com.spendanalyzer.security.SessionAuthenticator;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Deny by default. Public: the static pages, {@code /api/auth/**} and {@code /healthz}.
 * Everything else under {@code /api/} needs a logged-in user; anything unknown is refused.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/** No inline scripts or styles, no third-party hosts: the frontend must keep to this. */
	public static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
			+ "img-src 'self' data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; form-action 'self'; "
			+ "base-uri 'none'; frame-ancestors 'none'; object-src 'none'";

	private static final String[] STATIC_PAGES = { "/", "/index.html", "/login.html", "/css/**", "/js/**", "/img/**",
			"/favicon.ico", "/favicon.svg", "/manifest.webmanifest" };

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<SessionAuthenticator> authenticator,
			@Value("${app.origin:}") String appOrigin) throws Exception {
		http.csrf(csrf -> csrf.disable()) // replaced by SameOriginApiFilter + SameSite=Strict cookie
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			.logout(logout -> logout.disable())
			.requestCache(cache -> cache.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR)
				.permitAll()
				.requestMatchers("/api/auth/**")
				.permitAll()
				.requestMatchers(HttpMethod.GET, "/healthz")
				.permitAll()
				.requestMatchers("/api/**")
				.authenticated()
				.requestMatchers(HttpMethod.GET, STATIC_PAGES)
				.permitAll()
				.anyRequest()
				.denyAll())
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint((request, response, ex) -> writeJson(response,
						HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "Please log in."))
				.accessDeniedHandler((request, response, ex) -> writeJson(response, HttpServletResponse.SC_FORBIDDEN,
						"forbidden", "Not allowed.")))
			.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
				.frameOptions(frame -> frame.deny())
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
				.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
				.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
						"camera=(), microphone=(), geolocation=(), payment=()"))
				.addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
				.addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-origin")));

		http.addFilterBefore(new SameOriginApiFilter(appOrigin), AnonymousAuthenticationFilter.class);
		http.addFilterAfter(new SessionAuthFilter(authenticator), SameOriginApiFilter.class);
		return http.build();
	}

	/** Stops Spring Boot from creating its default user with a generated password. */
	@Bean
	public UserDetailsService noBuiltInUsers() {
		return username -> {
			throw new UsernameNotFoundException("not used");
		};
	}

	private static void writeJson(HttpServletResponse response, int status, String code, String message)
			throws java.io.IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-store");
		response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}

}
