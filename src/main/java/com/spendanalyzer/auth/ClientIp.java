package com.spendanalyzer.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address used for the per-IP limits. Only the address the server itself saw is used: in
 * production the container resolves the proxy headers (server.forward-headers-strategy=native), and
 * X-Forwarded-For is never parsed here because a client can put anything into it.
 */
final class ClientIp {

	private static final int MAX_LENGTH = 64;

	private ClientIp() {
	}

	static String of(HttpServletRequest request) {
		String address = request.getRemoteAddr();
		if (address == null || address.isBlank()) {
			return "unknown";
		}
		return address.length() > MAX_LENGTH ? address.substring(0, MAX_LENGTH) : address;
	}

}
