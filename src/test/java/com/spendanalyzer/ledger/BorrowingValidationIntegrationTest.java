package com.spendanalyzer.ledger;

import static com.spendanalyzer.ledger.LedgerApi.body;
import static com.spendanalyzer.ledger.LedgerApi.borrow;
import static com.spendanalyzer.ledger.LedgerApi.expense;
import static com.spendanalyzer.ledger.LedgerApi.income;
import static com.spendanalyzer.ledger.LedgerApi.repay;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** A bad BORROW or REPAY is refused with a 400 that names the field, and nothing at all is saved. */
@IntegrationTest
class BorrowingValidationIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	LedgerApi api;

	AuthUser user;

	@BeforeEach
	void setUp() {
		api = new LedgerApi(mvc);
		user = TestData.createUser(jdbc);
	}

	/** Every test here sends only rejected requests: the user must end with no entries, zero balances and no debt. */
	@AfterEach
	void nothingWasSaved() throws Exception {
		Long entries = jdbc.sql("SELECT count(*) FROM entries WHERE user_id = ?").param(user.id()).query(Long.class).single();
		assertThat(entries).isZero();
		api.assertBalances(user, "0.00", "0.00", "0.00");
		api.debts(user)
			.andExpect(jsonPath("$.total").value("0.00"))
			.andExpect(jsonPath("$.people").isEmpty())
			.andExpect(jsonPath("$.knownNames").isEmpty());
	}

	private void assertRejected(ResultActions result, String field) throws Exception {
		result.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation"))
			.andExpect(jsonPath("$.message").isString())
			.andExpect(jsonPath("$.fields.length()").value(1))
			.andExpect(jsonPath("$.fields." + field).isString());
	}

	/** Both kinds of request, built the same way. */
	private static List<Function<String, Map<String, Object>>> bothTypesFor(String amount, String account) {
		return List.of(person -> borrow(person, amount, account), person -> repay(person, amount, account));
	}

	// ---------------------------------------------------------------- person

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "   \t \n ", "\u0000", "\u0001\u0007\u001B\u007F", "  　", "​‍﻿",
			"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "Ravi Kumar Ravi Kumar Ravi Kumar Ravi Kum_" })
	void namesThatAreEmptyOnlySpacesOnlyControlCharactersOrTooLongAreRefused(String person) throws Exception {
		for (Function<String, Map<String, Object>> request : bothTypesFor("100", "BANK")) {
			assertRejected(api.create(user, request.apply(person)), "person");
		}
	}

	@Test
	void theNameIsRequired() throws Exception {
		for (String type : List.of("BORROW", "REPAY")) {
			assertRejected(api.create(user, body("type", type, "amount", "100", "account", "BANK")), "person");
			Map<String, Object> explicitNull = body("type", type, "amount", "100", "account", "BANK");
			explicitNull.put("person", null);
			assertRejected(api.create(user, explicitNull), "person");
		}
	}

	@Test
	void fortyOneCharactersAreTooManyAlsoWhenTheyAreEmoji() throws Exception {
		assertRejected(api.create(user, borrow("😀".repeat(41), "100", "CASH")), "person");
		// spaces around the name do not count, spaces inside do
		assertRejected(api.create(user, borrow("x".repeat(20) + " " + "y".repeat(20), "100", "CASH")), "person");
	}

	@Test
	void aNameMustBeTextNotAnObjectOrAList() throws Exception {
		Map<String, Object> asObject = borrow("Ravi", "100", "BANK");
		asObject.put("person", Map.of("name", "Ravi"));
		api.create(user, asObject).andExpect(status().isBadRequest());
		Map<String, Object> asList = borrow("Ravi", "100", "BANK");
		asList.put("person", List.of("Ravi", "Arun"));
		api.create(user, asList).andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------- account

	@Test
	void theAccountMustBeBankOrCash() throws Exception {
		for (String type : List.of("BORROW", "REPAY")) {
			// the card due is a debt of its own, never the way money from a friend moves
			assertRejected(api.create(user, body("type", type, "person", "Ravi", "amount", "100", "account", "CREDIT_CARD")),
					"account");
			assertRejected(api.create(user, body("type", type, "person", "Ravi", "amount", "100")), "account");
			assertRejected(api.create(user, body("type", type, "person", "Ravi", "amount", "100", "account", "")), "account");
			// "UPI" is a payment mode, not an account; names are exact
			assertRejected(api.create(user, body("type", type, "person", "Ravi", "amount", "100", "account", "UPI")), "account");
			assertRejected(api.create(user, body("type", type, "person", "Ravi", "amount", "100", "account", "bank")), "account");
			assertRejected(api.create(user, body("type", type, "person", "Ravi", "amount", "100", "account", "WALLET")),
					"account");
		}
	}

	// ---------------------------------------------------------------- amount, date, note

	@ParameterizedTest
	@ValueSource(strings = { "-5", "0", "1e9", "12.345", "NaN", "1,000", "", "0.00", "1000000.01", "Infinity", "abc",
			"99999999999999999999999999999", "1e400000000", "₹500", "+5" })
	void badAmountsAreRefused(String amount) throws Exception {
		assertRejected(api.create(user, borrow("Ravi", amount, "BANK")), "amount");
		assertRejected(api.create(user, repay("Ravi", amount, "CASH")), "amount");
	}

	@Test
	void aMissingAmountIsRefused() throws Exception {
		assertRejected(api.create(user, body("type", "BORROW", "person", "Ravi", "account", "BANK")), "amount");
		assertRejected(api.create(user, body("type", "REPAY", "person", "Ravi", "account", "BANK")), "amount");
	}

	@ParameterizedTest
	@ValueSource(strings = { "2999-01-01", "1999-12-31", "10-11-2026", "2026-02-30", "yesterday" })
	void badDatesAreRefused(String date) throws Exception {
		for (Function<String, Map<String, Object>> request : bothTypesFor("100", "BANK")) {
			Map<String, Object> body = request.apply("Ravi");
			body.put("date", date);
			assertRejected(api.create(user, body), "date");
		}
	}

	@Test
	void aDateMoreThanOneDayAheadIsRefused() throws Exception {
		Map<String, Object> request = borrow("Ravi", "100", "BANK");
		request.put("date", LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(3).toString());
		assertRejected(api.create(user, request), "date");
	}

	@Test
	void theNoteMayNotBeLongerThanAnyOtherDescription() throws Exception {
		Map<String, Object> request = borrow("Ravi", "100", "BANK");
		request.put("description", "x".repeat(121));
		assertRejected(api.create(user, request), "description");
	}

	// ---------------------------------------------------------------- fields of other entry types

	@Test
	void fieldsOfOtherEntryTypesAreRefused() throws Exception {
		for (String type : List.of("BORROW", "REPAY")) {
			assertRejected(api.create(user,
					body("type", type, "person", "Ravi", "amount", "100", "account", "BANK", "paymentMode", "UPI")), "paymentMode");
			assertRejected(api.create(user,
					body("type", type, "person", "Ravi", "amount", "100", "account", "BANK", "category", "SOCIAL_GIFTS")),
					"category");
			assertRejected(api.create(user,
					body("type", type, "person", "Ravi", "amount", "100", "account", "BANK", "newBalance", "100")), "newBalance");
		}
	}

	@Test
	void aFriendOnAnyOtherKindOfEntryIsRefused() throws Exception {
		Map<String, Object> spend = expense("juse", "40", "UPI");
		spend.put("person", "Ravi");
		assertRejected(api.create(user, spend), "person");
		Map<String, Object> money = income("40", "BANK");
		money.put("person", "Ravi");
		assertRejected(api.create(user, money), "person");
		assertRejected(api.create(user, body("type", "WITHDRAWAL", "amount", "40", "person", "Ravi")), "person");
		assertRejected(api.create(user, body("type", "CARD_PAYMENT", "amount", "40", "account", "BANK", "person", "Ravi")),
				"person");
		assertRejected(api.create(user, body("type", "ADJUSTMENT", "account", "BANK", "newBalance", "40", "person", "Ravi")),
				"person");
	}

	@Test
	void unknownPropertiesAreRefused() throws Exception {
		for (String extra : new String[] { "personKey", "person_key", "owed", "name", "friend", "userId" }) {
			Map<String, Object> request = borrow("Ravi", "100", "BANK");
			request.put(extra, "x");
			api.create(user, request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("bad_request"));
		}
	}

	@Test
	void theClientIdMustBeAUuid() throws Exception {
		Map<String, Object> missing = borrow("Ravi", "100", "BANK");
		missing.remove("clientId");
		assertRejected(api.create(user, missing), "clientId");
		Map<String, Object> wrong = repay("Ravi", "100", "BANK");
		wrong.put("clientId", "12345");
		assertRejected(api.create(user, wrong), "clientId");
	}

	// ---------------------------------------------------------------- nothing owed

	@Test
	void payingBackWithoutEverHavingBorrowedIsRefused() throws Exception {
		assertRejected(api.create(user, repay("Ravi", "100", "BANK")), "person");
		assertRejected(api.create(user, repay("Ravi", "0.01", "CASH")), "person");
	}

}
