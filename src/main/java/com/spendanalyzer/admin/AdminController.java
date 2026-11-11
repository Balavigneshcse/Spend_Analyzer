package com.spendanalyzer.admin;

import com.spendanalyzer.common.ApiException;
import com.spendanalyzer.security.AuthUser;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/admin/stats: the numbers the administrator sees on the dashboard. Login is required by
 * the /api/** rule; a logged-in user who is not an administrator gets the same 404 as any unknown
 * path, so the endpoint does not advertise itself.
 */
@RestController
public class AdminController {

	private final AdminAccess access;

	private final AdminStatsService stats;

	AdminController(AdminAccess access, AdminStatsService stats) {
		this.access = access;
		this.stats = stats;
	}

	@GetMapping("/api/admin/stats")
	public ResponseEntity<AdminStatsService.Stats> stats(@AuthenticationPrincipal AuthUser user) {
		if (user == null || !access.isAdmin(user.email())) {
			// the same body GlobalExceptionHandler gives an unknown path
			throw ApiException.notFound("Not found.");
		}
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(stats.stats());
	}

}
