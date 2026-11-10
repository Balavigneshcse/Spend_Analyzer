package com.spendanalyzer.ledger;

import static com.spendanalyzer.ledger.LedgerApi.body;
import static com.spendanalyzer.ledger.LedgerApi.expense;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/balances and the one-time opening balances. */
@IntegrationTest
class BalancesIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Clock clock;

	@Autowired
	ZoneId appZone;

	LedgerApi api;

	@BeforeEach
	void setUp() {
		api = new LedgerApi(mvc);
	}

	@Test
	void needsLogin() throws Exception {
		mvc.perform(get("/api/balances")).andExpect(status().isUnauthorized());
		mvc.perform(put("/api/balances/opening").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void aNewUserStartsAtZeroNotOnboardedAndGetsThreeAccountRows() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.balances(user)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bank").value("0.00"))
			.andExpect(jsonPath("$.cash").value("0.00"))
			.andExpect(jsonPath("$.creditCardDue").value("0.00"))
			.andExpect(jsonPath("$.available").value("0.00"))
			.andExpect(jsonPath("$.onboarded").value(false))
			.andExpect(jsonPath("$.owedToFriends").value("0.00"))
			.andExpect(jsonPath("$.length()").value(6));
		api.balances(user).andExpect(status().isOk());
		List<String> types = jdbc.sql("SELECT type FROM accounts WHERE user_id = ? ORDER BY type")
			.param(user.id())
			.query(String.class)
			.list();
		assertThat(types).containsExactly("BANK", "CASH", "CREDIT_CARD");
	}

	@Test
	void onboardingSetsTheBalancesOnceAndTheLedgerExplainsThem() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.opening(user, body("bank", "5200", "cash", "340", "creditCardDue", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bank").value("5200.00"))
			.andExpect(jsonPath("$.cash").value("340.00"))
			.andExpect(jsonPath("$.creditCardDue").value("0.00"))
			.andExpect(jsonPath("$.available").value("5540.00"))
			.andExpect(jsonPath("$.onboarded").value(true));

		// one ADJUSTMENT per non-zero amount, dated today (India)
		List<Map<String, Object>> rows = jdbc
			.sql("SELECT type, account, amount, description, entry_date FROM entries WHERE user_id = ? ORDER BY account")
			.param(user.id())
			.query()
			.listOfRows();
		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(r -> r.get("type")).containsOnly("ADJUSTMENT");
		assertThat(rows).extracting(r -> r.get("account")).containsExactly("BANK", "CASH");
		assertThat(rows).extracting(r -> r.get("amount").toString()).containsExactly("5200.00", "340.00");
		assertThat(rows).extracting(r -> r.get("entry_date").toString())
			.containsOnly(LocalDate.now(clock.withZone(appZone)).toString());

		// the opening entries show up in the list like any other entry
		api.list(user, "?type=ADJUSTMENT").andExpect(jsonPath("$.total").value(2));

		// a second submit is refused and changes nothing
		api.opening(user, body("bank", "9999", "cash", "9999", "creditCardDue", "9999"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("already_onboarded"));
		api.assertBalances(user, "5200.00", "340.00", "0.00");
		api.balances(user).andExpect(jsonPath("$.onboarded").value(true));
	}

	@Test
	void creditCardDueIsOptionalAndCanBeGiven() throws Exception {
		AuthUser without = TestData.createUser(jdbc);
		api.opening(without, body("bank", "100", "cash", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.creditCardDue").value("0.00"))
			.andExpect(jsonPath("$.onboarded").value(true));

		AuthUser with = TestData.createUser(jdbc);
		api.opening(with, body("bank", "100.5", "cash", "20", "creditCardDue", "1500.75"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bank").value("100.50"))
			.andExpect(jsonPath("$.creditCardDue").value("1500.75"))
			// what you owe is not part of what you have
			.andExpect(jsonPath("$.available").value("120.50"));
	}

	@Test
	void allZeroOpeningStillOnboardsAndWritesNoEntries() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.opening(user, body("bank", "0", "cash", "0", "creditCardDue", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.onboarded").value(true));
		assertThat(countEntries(user)).isZero();
	}

	@Test
	void openingStatesTheBalanceNowEvenIfSomethingWasLoggedBefore() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.created(user, expense("juse", "40", "UPI"));
		api.assertBalances(user, "-40.00", "0.00", "0.00");
		api.onboard(user, "5200", "340", "0");
		// "my bank balance is 5200" means 5200, and the ledger still adds up: -40 + 5240
		api.assertBalances(user, "5200.00", "340.00", "0.00");
		String adjustment = jdbc
			.sql("SELECT amount::text FROM entries WHERE user_id = ? AND type = 'ADJUSTMENT' AND account = 'BANK'")
			.param(user.id())
			.query(String.class)
			.single();
		assertThat(adjustment).isEqualTo("5240.00");
	}

	@Test
	void badOpeningAmountsAreRefusedAndNothingIsSaved() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.opening(user, body("bank", "-1", "cash", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation"))
			.andExpect(jsonPath("$.fields.bank").exists());
		api.opening(user, body("bank", "10"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.cash").exists());
		api.opening(user, body("bank", "10", "cash", "1,000"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.cash").exists());
		api.opening(user, body("bank", "1000000000", "cash", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.bank").exists());
		api.opening(user, body("bank", "10", "cash", "0", "creditCardDue", "12.345"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.creditCardDue").exists());
		api.opening(user, body("bank", "10", "cash", "0", "onboarded", true)).andExpect(status().isBadRequest());
		api.opening(user, body("bank", "10", "cash", "0", "userId", "x")).andExpect(status().isBadRequest());

		api.balances(user).andExpect(jsonPath("$.onboarded").value(false)).andExpect(jsonPath("$.bank").value("0.00"));
		assertThat(countEntries(user)).isZero();
	}

	private long countEntries(AuthUser user) {
		return jdbc.sql("SELECT count(*) FROM entries WHERE user_id = ?").param(user.id()).query(Long.class).single();
	}

}
