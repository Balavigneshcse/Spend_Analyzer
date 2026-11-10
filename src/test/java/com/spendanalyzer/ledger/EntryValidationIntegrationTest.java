package com.spendanalyzer.ledger;

import static com.spendanalyzer.ledger.LedgerApi.body;
import static com.spendanalyzer.ledger.LedgerApi.expense;
import static com.spendanalyzer.ledger.LedgerApi.income;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Bad input is refused with a 400 that names the field, and nothing at all is saved. */
@IntegrationTest
class EntryValidationIntegrationTest {

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

	/** Every test here sends only rejected requests: the user must end with no entries and zero balances. */
	@AfterEach
	void nothingWasSaved() throws Exception {
		Long entries = jdbc.sql("SELECT count(*) FROM entries WHERE user_id = ?").param(user.id()).query(Long.class).single();
		assertThat(entries).isZero();
		api.assertBalances(user, "0.00", "0.00", "0.00");
	}

	private void assertRejected(ResultActions result, String field) throws Exception {
		result.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation"))
			.andExpect(jsonPath("$.message").isString())
			.andExpect(jsonPath("$.fields." + field).isString());
	}

	@ParameterizedTest
	@ValueSource(strings = { "-5", "0", "1e9", "12.345", "NaN", "1,000", "", "0.00", "1000000.01", "Infinity", "abc",
			"99999999999999999999999999999", "1e400000000" })
	void badAmountsAreRefused(String amount) throws Exception {
		assertRejected(api.create(user, expense("juse", amount, "UPI")), "amount");
		assertRejected(api.create(user, income(amount, "BANK")), "amount");
		assertRejected(api.create(user, body("type", "WITHDRAWAL", "amount", amount)), "amount");
		assertRejected(api.create(user, body("type", "CARD_PAYMENT", "amount", amount, "account", "BANK")), "amount");
	}

	@Test
	void aMissingAmountIsRefused() throws Exception {
		assertRejected(api.create(user, body("type", "EXPENSE", "description", "juse", "paymentMode", "UPI")), "amount");
	}

	@ParameterizedTest
	@ValueSource(strings = { "-5", "1e9", "12.345", "NaN", "1,000", "", "1000000000" })
	void badNewBalancesAreRefused(String newBalance) throws Exception {
		assertRejected(api.create(user, body("type", "ADJUSTMENT", "account", "BANK", "newBalance", newBalance)),
				"newBalance");
	}

	@Test
	void badEnumsAreRefusedAndTheFieldIsNamed() throws Exception {
		assertRejected(api.create(user, expense("juse", "40", "PAYTM")), "paymentMode");
		assertRejected(api.create(user, expense("juse", "40", "upi")), "paymentMode");
		assertRejected(api.create(user, body("type", "EXPENSE", "description", "juse", "amount", "40")), "paymentMode");

		Map<String, Object> badCategory = expense("juse", "40", "UPI");
		badCategory.put("category", "JUICE");
		assertRejected(api.create(user, badCategory), "category");

		assertRejected(api.create(user, body("type", "SPEND", "description", "juse", "amount", "40", "paymentMode", "UPI")),
				"type");
		assertRejected(api.create(user, body("clientId", UUID.randomUUID().toString(), "amount", "40")), "type");

		assertRejected(api.create(user, income("40", "WALLET")), "account");
		assertRejected(api.create(user, income("40", "CREDIT_CARD")), "account");
		assertRejected(api.create(user, body("type", "INCOME", "amount", "40")), "account");
		assertRejected(api.create(user, body("type", "ADJUSTMENT", "account", "SAVINGS", "newBalance", "10")), "account");
	}

	@Test
	void anExpenseCannotPickItsAccount() throws Exception {
		Map<String, Object> request = expense("juse", "40", "UPI");
		request.put("account", "CREDIT_CARD");
		assertRejected(api.create(user, request), "account");
	}

	@ParameterizedTest
	@ValueSource(strings = { "2999-01-01", "1999-12-31", "10-11-2026", "2026-02-30", "yesterday", "2026-11-10T00:00:00Z" })
	void badDatesAreRefused(String date) throws Exception {
		Map<String, Object> request = expense("juse", "40", "UPI");
		request.put("date", date);
		assertRejected(api.create(user, request), "date");
	}

	@Test
	void aDateMoreThanOneDayAheadIsRefused() throws Exception {
		Map<String, Object> request = expense("juse", "40", "UPI");
		request.put("date", LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(3).toString());
		assertRejected(api.create(user, request), "date");
	}

	@Test
	void descriptionMustBeOneTo120Characters() throws Exception {
		assertRejected(api.create(user, expense("x".repeat(121), "40", "UPI")), "description");
		assertRejected(api.create(user, expense("", "40", "UPI")), "description");
		assertRejected(api.create(user, expense("   \t ", "40", "UPI")), "description");
		assertRejected(api.create(user, body("type", "EXPENSE", "amount", "40", "paymentMode", "UPI")), "description");
		Map<String, Object> longNote = income("40", "BANK");
		longNote.put("description", "x".repeat(121));
		assertRejected(api.create(user, longNote), "description");
	}

	@Test
	void clientIdMustBeAUuid() throws Exception {
		Map<String, Object> missing = expense("juse", "40", "UPI");
		missing.remove("clientId");
		assertRejected(api.create(user, missing), "clientId");
		Map<String, Object> wrong = expense("juse", "40", "UPI");
		wrong.put("clientId", "12345");
		assertRejected(api.create(user, wrong), "clientId");
	}

	@Test
	void fieldsOfAnotherEntryTypeAreRefused() throws Exception {
		Map<String, Object> incomeWithMode = income("40", "BANK");
		incomeWithMode.put("paymentMode", "UPI");
		assertRejected(api.create(user, incomeWithMode), "paymentMode");
		Map<String, Object> incomeWithCategory = income("40", "BANK");
		incomeWithCategory.put("category", "SHOPPING");
		assertRejected(api.create(user, incomeWithCategory), "category");
		Map<String, Object> expenseWithNewBalance = expense("juse", "40", "UPI");
		expenseWithNewBalance.put("newBalance", "100");
		assertRejected(api.create(user, expenseWithNewBalance), "newBalance");
	}

	@Test
	void unknownPropertiesAreRefused() throws Exception {
		for (String extra : new String[] { "userId", "user_id", "id", "balance", "aiConfidence", "userCorrected",
				"needsReview", "createdAt" }) {
			Map<String, Object> request = expense("juse", "40", "UPI");
			request.put(extra, "x");
			api.create(user, request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("bad_request"));
		}
	}

	@Test
	void unreadableBodiesAreRefused() throws Exception {
		for (String content : new String[] { "{", "[]", "null", "\"text\"", "{\"amount\": {\"a\": 1}}" }) {
			mvc.perform(post("/api/entries").with(TestData.as(user)).contentType(MediaType.APPLICATION_JSON).content(content))
				.andExpect(status().isBadRequest());
		}
		mvc.perform(post("/api/entries").with(TestData.as(user)).contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest());
	}

	/**
	 * The contract says money is sent as text. If a client sends a bare JSON number anyway, its
	 * literal text goes through the very same check, so nothing is ever rounded or read as a float.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "1e2", "0.1e1", "12.345", "-5", "0", "0.30000000000000004", "1e400000000", "true" })
	void bareJsonNumbersGetTheSameStrictCheck(String literal) throws Exception {
		String content = "{\"clientId\":\"" + UUID.randomUUID()
				+ "\",\"type\":\"EXPENSE\",\"description\":\"juse\",\"paymentMode\":\"UPI\",\"amount\":" + literal + "}";
		ResultActions result = mvc
			.perform(post("/api/entries").with(TestData.as(user)).contentType(MediaType.APPLICATION_JSON).content(content));
		assertRejected(result, "amount");
	}

	@Test
	void previewNeedsADescriptionAndSavesNothing() throws Exception {
		mvc.perform(post("/api/entries/preview").with(TestData.as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"description\": \"  \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.description").isString());
		mvc.perform(post("/api/entries/preview").with(TestData.as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(LedgerApi.json(body("description", "x".repeat(121)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.description").isString());
		mvc.perform(post("/api/entries/preview").with(TestData.as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"description\": \"juse\", \"category\": \"SHOPPING\"}")).andExpect(status().isBadRequest());

		// a good preview: the documented shape, with labels, and still nothing saved
		mvc.perform(post("/api/entries/preview").with(TestData.as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"description\": \"juse\", \"amount\": \"4\", \"paymentMode\": \"UPI\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(8))
			.andExpect(jsonPath("$.category").isString())
			.andExpect(jsonPath("$.categoryLabel").isString())
			.andExpect(jsonPath("$.confidence").isNumber())
			.andExpect(jsonPath("$.item").isString())
			.andExpect(jsonPath("$.explanation").isString())
			.andExpect(jsonPath("$.needsReview").isBoolean())
			.andExpect(jsonPath("$.source").isString())
			.andExpect(jsonPath("$.alternatives").isArray());
		// half-typed hints are ignored, not refused
		mvc.perform(post("/api/entries/preview").with(TestData.as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"description\": \"juse\", \"amount\": \"4.\", \"paymentMode\": \"\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/entries/preview").with(TestData.as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"description\": \"juse\"}")).andExpect(status().isOk());
	}

}
