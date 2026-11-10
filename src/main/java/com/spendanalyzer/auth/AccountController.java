package com.spendanalyzer.auth;

import com.spendanalyzer.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The endpoints under /api/account (login required, see docs/API.md). */
@RestController
@RequestMapping("/api/account")
public class AccountController {

	private final AccountService account;

	private final SessionService sessions;

	AccountController(AccountService account, SessionService sessions) {
		this.account = account;
		this.sessions = sessions;
	}

	@PostMapping("/password")
	public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthUser user,
			@RequestBody AuthRequests.ChangePassword body, HttpServletRequest request) {
		account.changePassword(user.id(), body, ClientIp.of(request), sessions.tokenOf(request));
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser user,
			@RequestBody AuthRequests.DeleteAccount body, HttpServletRequest request) {
		account.delete(user.id(), body, ClientIp.of(request));
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, sessions.clearingCookie().toString())
			.build();
	}

}
