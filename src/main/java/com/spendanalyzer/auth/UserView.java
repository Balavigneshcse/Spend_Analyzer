package com.spendanalyzer.auth;

/**
 * The "User" shape of docs/API.md: name, email, the masked phone number, and whether this user is an
 * administrator (only ever about the user themself). Nothing else leaves the server.
 */
public record UserView(String name, String email, String phoneMasked, boolean admin) {

	/** A user who is not an administrator. */
	public UserView(String name, String email, String phoneMasked) {
		this(name, email, phoneMasked, false);
	}

	public UserView withAdmin(boolean isAdmin) {
		return new UserView(name, email, phoneMasked, isAdmin);
	}

}
