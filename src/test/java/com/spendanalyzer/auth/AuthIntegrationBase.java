package com.spendanalyzer.auth;

import com.spendanalyzer.auth.mail.MailDispatcher;
import com.spendanalyzer.testsupport.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** What every integration test of the auth module needs. */
@IntegrationTest
abstract class AuthIntegrationBase {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	CapturingEmailSender mailbox;

	@Autowired
	MailDispatcher dispatcher;

	@Autowired
	PasswordHasher hasher;

	AuthClient client;

	@BeforeEach
	void newClient() {
		client = new AuthClient(mvc, jdbc, mailbox, dispatcher, hasher);
	}

}
