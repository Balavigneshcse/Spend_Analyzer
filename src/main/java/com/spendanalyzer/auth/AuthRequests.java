package com.spendanalyzer.auth;

/**
 * The JSON bodies of the auth and account endpoints. Every field is a string and is checked by
 * hand in the services (the rules are in docs/API.md).
 * <p>
 * Records print all their fields in toString(), so every record that carries a password or a code
 * overrides it: a stray log statement or exception message must never contain a secret.
 */
public final class AuthRequests {

	private AuthRequests() {
	}

	public record Register(String name, String email, String phone, String password) {

		@Override
		public String toString() {
			return "Register[***]";
		}

	}

	public record VerifyRegistration(String registrationId, String code) {

		@Override
		public String toString() {
			return "VerifyRegistration[registrationId=" + registrationId + "]";
		}

	}

	public record ResendRegistration(String registrationId) {
	}

	public record Login(String email, String password) {

		@Override
		public String toString() {
			return "Login[***]";
		}

	}

	public record ResetRequest(String email) {

		@Override
		public String toString() {
			return "ResetRequest[***]";
		}

	}

	public record ResetResend(String resetId) {
	}

	public record ResetConfirm(String resetId, String code, String newPassword) {

		@Override
		public String toString() {
			return "ResetConfirm[resetId=" + resetId + "]";
		}

	}

	public record ChangePassword(String currentPassword, String newPassword) {

		@Override
		public String toString() {
			return "ChangePassword[***]";
		}

	}

	public record DeleteAccount(String password) {

		@Override
		public String toString() {
			return "DeleteAccount[***]";
		}

	}

}
