package com.spendanalyzer.security;

import java.util.UUID;

/**
 * The logged-in user. This is the principal of every authenticated request:
 * controllers receive it with {@code @AuthenticationPrincipal AuthUser user}.
 * The user id must only ever come from here, never from the request.
 */
public record AuthUser(UUID id, String email, String name) {
}
