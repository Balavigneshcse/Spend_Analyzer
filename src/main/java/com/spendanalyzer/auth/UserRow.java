package com.spendanalyzer.auth;

import java.util.UUID;

/** A row of the users table as the auth module needs it. */
public record UserRow(UUID id, String email, String name, String phone, String passwordHash) {

	/** What the browser may see of a user. */
	public UserView view() {
		return new UserView(name, email, IndianPhone.mask(phone));
	}

	@Override
	public String toString() {
		// never print the hash or the phone number
		return "UserRow[id=" + id + "]";
	}

}
