package com.spendanalyzer.auth;

import java.util.UUID;

import com.spendanalyzer.admin.AdminAccess;
import com.spendanalyzer.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public endpoints under /api/auth (see docs/API.md). The controller only moves data between
 * HTTP and the services; every rule lives in the services. No answer ever contains a code.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private static final int EXPIRES_IN_SECONDS = (int) OtpService.CODE_LIFETIME.toSeconds();

	private static final int RESEND_AFTER_SECONDS = (int) SendLimits.RESEND_COOLDOWN.toSeconds();

	/** idleMinutes: the page logs out after this many minutes without activity (the server decides too). */
	public record ConfigResponse(boolean registrationEnabled, int idleMinutes) {
	}

	public record RegisterResponse(UUID registrationId, int expiresInSeconds, int resendAfterSeconds) {
	}

	public record ResetResponse(UUID resetId, int expiresInSeconds, int resendAfterSeconds) {
	}

	public record ResendResponse(int expiresInSeconds, int resendAfterSeconds) {
	}

	public record UserResponse(UserView user) {
	}

	private final RegistrationService registration;

	private final LoginService login;

	private final PasswordResetService passwordReset;

	private final AccountService account;

	private final SessionService sessions;

	private final AdminAccess admins;

	AuthController(RegistrationService registration, LoginService login, PasswordResetService passwordReset,
			AccountService account, SessionService sessions, AdminAccess admins) {
		this.registration = registration;
		this.login = login;
		this.passwordReset = passwordReset;
		this.account = account;
		this.sessions = sessions;
		this.admins = admins;
	}

	@GetMapping("/config")
	public ConfigResponse config() {
		return new ConfigResponse(registration.enabled(), sessions.idleMinutes());
	}

	@PostMapping("/register")
	public RegisterResponse register(@RequestBody AuthRequests.Register body, HttpServletRequest request) {
		UUID registrationId = registration.register(body, ClientIp.of(request));
		return new RegisterResponse(registrationId, EXPIRES_IN_SECONDS, RESEND_AFTER_SECONDS);
	}

	@PostMapping("/register/verify")
	public ResponseEntity<UserResponse> verify(@RequestBody AuthRequests.VerifyRegistration body) {
		RegistrationService.Registered registered = registration.verify(body.registrationId(), body.code());
		return loggedIn(registered.user(), registered.sessionToken());
	}

	@PostMapping("/register/resend")
	public ResendResponse resendRegistration(@RequestBody AuthRequests.ResendRegistration body,
			HttpServletRequest request) {
		registration.resend(body.registrationId(), ClientIp.of(request));
		return new ResendResponse(EXPIRES_IN_SECONDS, RESEND_AFTER_SECONDS);
	}

	@PostMapping("/login")
	public ResponseEntity<UserResponse> login(@RequestBody AuthRequests.Login body, HttpServletRequest request) {
		LoginService.LoggedIn result = login.login(body, ClientIp.of(request), sessions.tokenOf(request));
		return loggedIn(result.user(), result.sessionToken());
	}

	/** Safe to call when not logged in: it then only clears the cookie. */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		sessions.delete(sessions.tokenOf(request));
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, sessions.clearingCookie().toString())
			.build();
	}

	/**
	 * The page's keep-alive while the user reads or types without saving. The session filter has
	 * already refreshed last_seen_at; this only has to say whether the session is still there.
	 */
	@PostMapping("/ping")
	public ResponseEntity<Void> ping(@AuthenticationPrincipal AuthUser user) {
		if (user == null) {
			throw AuthErrors.unauthorized();
		}
		return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
	}

	@GetMapping("/me")
	public UserResponse me(@AuthenticationPrincipal AuthUser user) {
		// /api/auth/** is public, so "not logged in" has to be answered here
		if (user == null) {
			throw AuthErrors.unauthorized();
		}
		return new UserResponse(viewOf(account.current(user.id())));
	}

	@PostMapping("/password-reset/request")
	public ResetResponse requestReset(@RequestBody AuthRequests.ResetRequest body, HttpServletRequest request) {
		UUID resetId = passwordReset.request(body.email(), ClientIp.of(request));
		return new ResetResponse(resetId, EXPIRES_IN_SECONDS, RESEND_AFTER_SECONDS);
	}

	@PostMapping("/password-reset/resend")
	public ResendResponse resendReset(@RequestBody AuthRequests.ResetResend body, HttpServletRequest request) {
		passwordReset.resend(body.resetId(), ClientIp.of(request));
		return new ResendResponse(EXPIRES_IN_SECONDS, RESEND_AFTER_SECONDS);
	}

	@PostMapping("/password-reset/confirm")
	public ResponseEntity<Void> confirmReset(@RequestBody AuthRequests.ResetConfirm body) {
		passwordReset.confirm(body.resetId(), body.code(), body.newPassword());
		return ResponseEntity.noContent().build();
	}

	private ResponseEntity<UserResponse> loggedIn(UserRow user, String sessionToken) {
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, sessions.cookie(sessionToken).toString())
			.body(new UserResponse(viewOf(user)));
	}

	/** The user as the page sees them, with the admin flag (true only for an email in app.admin.emails). */
	private UserView viewOf(UserRow user) {
		return user.view().withAdmin(admins.isAdmin(user.email()));
	}

}
