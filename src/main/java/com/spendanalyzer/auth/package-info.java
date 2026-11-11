/**
 * Accounts and login: registration with an emailed code, login, logout, password reset, sessions,
 * password change and account deletion (the "Auth" and "Account" endpoints of docs/API.md).
 * <p>
 * Where to look:
 * <ul>
 * <li>{@link com.spendanalyzer.auth.AuthController}, {@link com.spendanalyzer.auth.AccountController}:
 * the HTTP endpoints, nothing else.</li>
 * <li>{@link com.spendanalyzer.auth.RegistrationService}, {@link com.spendanalyzer.auth.LoginService},
 * {@link com.spendanalyzer.auth.PasswordResetService}, {@link com.spendanalyzer.auth.AccountService}:
 * one class per user journey.</li>
 * <li>{@link com.spendanalyzer.auth.OtpService}: the six digit codes (5 tries, 10 minutes, one use, one purpose).</li>
 * <li>{@code SendLimits}, {@code LoginThrottle}: every rate limit, all counted in the database.</li>
 * <li>{@link com.spendanalyzer.auth.SessionService}: the session cookie; it is the
 * {@link com.spendanalyzer.security.SessionAuthenticator} the security filter asks.</li>
 * <li>{@link com.spendanalyzer.auth.PasswordPolicy}, {@link com.spendanalyzer.auth.PasswordHasher}:
 * the password rule and BCrypt.</li>
 * <li>{@code com.spendanalyzer.auth.mail}: the mail texts and the senders (the owner's Gmail
 * through a Google Apps Script, or Brevo, in production; the console in development).</li>
 * </ul>
 * Two rules run through all of it: an answer never reveals whether an email address has an
 * account, and a code or password is never logged or sent back.
 */
package com.spendanalyzer.auth;
