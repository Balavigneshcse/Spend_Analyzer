package com.spendanalyzer.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.TestData;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Small driver for the ledger endpoints, shared by the integration tests of this package. */
final class LedgerApi {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final MockMvc mvc;

	LedgerApi(MockMvc mvc) {
		this.mvc = mvc;
	}

	// ---------------------------------------------------------------- request bodies

	/** A mutable JSON object from key/value pairs; a fresh clientId is added when a type is given. */
	static Map<String, Object> body(Object... keysAndValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		if (map.containsKey("type") && !map.containsKey("clientId")) {
			map.put("clientId", UUID.randomUUID().toString());
		}
		return map;
	}

	static Map<String, Object> expense(String description, String amount, String mode) {
		return body("type", "EXPENSE", "description", description, "amount", amount, "paymentMode", mode);
	}

	static Map<String, Object> income(String amount, String account) {
		return body("type", "INCOME", "amount", amount, "account", account);
	}

	static Map<String, Object> withdrawal(String amount) {
		return body("type", "WITHDRAWAL", "amount", amount);
	}

	static Map<String, Object> cardPayment(String amount, String account) {
		return body("type", "CARD_PAYMENT", "amount", amount, "account", account);
	}

	static Map<String, Object> adjustment(String account, String newBalance) {
		return body("type", "ADJUSTMENT", "account", account, "newBalance", newBalance);
	}

	/** Money from a friend: {@code account} is BANK (by UPI) or CASH. */
	static Map<String, Object> borrow(String person, String amount, String account) {
		return body("type", "BORROW", "person", person, "amount", amount, "account", account);
	}

	/** Paying a friend back: {@code account} is BANK (by UPI) or CASH. */
	static Map<String, Object> repay(String person, String amount, String account) {
		return body("type", "REPAY", "person", person, "amount", amount, "account", account);
	}

	static String json(Map<String, ?> body) {
		return JSON.writeValueAsString(body);
	}

	// ---------------------------------------------------------------- calls

	ResultActions create(AuthUser user, Map<String, ?> body) throws Exception {
		return mvc.perform(post("/api/entries").with(TestData.as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json(body)));
	}

	/** Creates an entry, expects 201 and returns its id. */
	String created(AuthUser user, Map<String, ?> body) throws Exception {
		return read(create(user, body).andExpect(status().isCreated()), "$.entry.id");
	}

	ResultActions edit(AuthUser user, String id, Map<String, ?> body) throws Exception {
		return mvc.perform(patch("/api/entries/{id}", id).with(TestData.as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json(body)));
	}

	ResultActions remove(AuthUser user, String id) throws Exception {
		return mvc.perform(delete("/api/entries/{id}", id).with(TestData.as(user)));
	}

	ResultActions entry(AuthUser user, String id) throws Exception {
		return mvc.perform(get("/api/entries/{id}", id).with(TestData.as(user)));
	}

	/**
	 * {@code query} looks like {@code "?type=EXPENSE&size=5"}. Values are sent exactly as written
	 * (write {@code q=100%}, not {@code q=100%25}); a value cannot contain {@code &}.
	 */
	ResultActions list(AuthUser user, String query) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/entries").with(TestData.as(user));
		String pairs = query.startsWith("?") ? query.substring(1) : query;
		if (!pairs.isEmpty()) {
			for (String pair : pairs.split("&")) {
				int equals = pair.indexOf('=');
				request.queryParam(pair.substring(0, equals), pair.substring(equals + 1));
			}
		}
		return mvc.perform(request);
	}

	ResultActions balances(AuthUser user) throws Exception {
		return mvc.perform(get("/api/balances").with(TestData.as(user)));
	}

	ResultActions debts(AuthUser user) throws Exception {
		return mvc.perform(get("/api/debts").with(TestData.as(user)));
	}

	ResultActions opening(AuthUser user, Map<String, ?> body) throws Exception {
		return mvc.perform(put("/api/balances/opening").with(TestData.as(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json(body)));
	}

	/** Enters opening balances and expects success. */
	void onboard(AuthUser user, String bank, String cash, String creditCardDue) throws Exception {
		opening(user, body("bank", bank, "cash", cash, "creditCardDue", creditCardDue)).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- answers

	static <T> T read(ResultActions result, String jsonPath) throws Exception {
		return JsonPath.read(text(result), jsonPath);
	}

	static String text(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	/**
	 * The ledger invariant: every balance equals the sum of the signed effects of the user's
	 * entries. The effects are spelled out in SQL here on purpose, independent of the production
	 * code that applies them.
	 */
	static void assertLedgerExplainsBalances(JdbcClient jdbc, AuthUser user) {
		Map<String, Object> sums = jdbc.sql("""
				SELECT
					COALESCE(SUM(CASE
						WHEN type = 'EXPENSE' AND account = 'BANK' THEN -amount
						WHEN type = 'INCOME' AND account = 'BANK' THEN amount
						WHEN type = 'WITHDRAWAL' THEN -amount
						WHEN type = 'CARD_PAYMENT' AND account = 'BANK' THEN -amount
						WHEN type = 'ADJUSTMENT' AND account = 'BANK' THEN amount
						WHEN type = 'BORROW' AND account = 'BANK' THEN amount
						WHEN type = 'REPAY' AND account = 'BANK' THEN -amount
						ELSE 0 END), 0) AS bank,
					COALESCE(SUM(CASE
						WHEN type = 'EXPENSE' AND account = 'CASH' THEN -amount
						WHEN type = 'INCOME' AND account = 'CASH' THEN amount
						WHEN type = 'WITHDRAWAL' THEN amount
						WHEN type = 'CARD_PAYMENT' AND account = 'CASH' THEN -amount
						WHEN type = 'ADJUSTMENT' AND account = 'CASH' THEN amount
						WHEN type = 'BORROW' AND account = 'CASH' THEN amount
						WHEN type = 'REPAY' AND account = 'CASH' THEN -amount
						ELSE 0 END), 0) AS cash,
					COALESCE(SUM(CASE
						WHEN type = 'EXPENSE' AND account = 'CREDIT_CARD' THEN amount
						WHEN type = 'CARD_PAYMENT' THEN -amount
						WHEN type = 'ADJUSTMENT' AND account = 'CREDIT_CARD' THEN amount
						ELSE 0 END), 0) AS card
				FROM entries WHERE user_id = ?
				""").param(user.id()).query().singleRow();
		assertThat(balanceInDb(jdbc, user, "BANK")).as("bank").isEqualByComparingTo((BigDecimal) sums.get("bank"));
		assertThat(balanceInDb(jdbc, user, "CASH")).as("cash").isEqualByComparingTo((BigDecimal) sums.get("cash"));
		assertThat(balanceInDb(jdbc, user, "CREDIT_CARD")).as("credit card due")
			.isEqualByComparingTo((BigDecimal) sums.get("card"));
	}

	/**
	 * The debt invariant: no friend is ever owed less than nothing. Spelled out in SQL here on
	 * purpose, independent of the production code that guards it.
	 * @return what the user owes all friends together, straight from the entries
	 */
	static BigDecimal assertNoFriendIsOwedLessThanNothing(JdbcClient jdbc, AuthUser user) {
		List<Map<String, Object>> perFriend = jdbc.sql("""
				SELECT person_key, SUM(CASE WHEN type = 'BORROW' THEN amount ELSE -amount END) AS owed
				FROM entries WHERE user_id = ? AND type IN ('BORROW', 'REPAY')
				GROUP BY person_key
				""").param(user.id()).query().listOfRows();
		BigDecimal total = BigDecimal.ZERO;
		for (Map<String, Object> friend : perFriend) {
			BigDecimal owed = (BigDecimal) friend.get("owed");
			assertThat(owed).as("owed to %s", friend.get("person_key")).isGreaterThanOrEqualTo(BigDecimal.ZERO);
			total = total.add(owed);
		}
		return total;
	}

	/** The stored balance; zero when the account row does not exist yet. */
	static BigDecimal balanceInDb(JdbcClient jdbc, AuthUser user, String account) {
		return jdbc.sql("SELECT balance FROM accounts WHERE user_id = ? AND type = ?")
			.params(user.id(), account)
			.query(BigDecimal.class)
			.optional()
			.orElse(BigDecimal.ZERO);
	}

	/** Asserts the three balances exactly as the API prints them, e.g. {@code "5160.00"}. */
	void assertBalances(AuthUser user, String bank, String cash, String creditCardDue) throws Exception {
		Map<String, Object> answer = read(balances(user).andExpect(status().isOk()), "$");
		assertThat(answer).containsEntry("bank", bank)
			.containsEntry("cash", cash)
			.containsEntry("creditCardDue", creditCardDue);
	}

}
