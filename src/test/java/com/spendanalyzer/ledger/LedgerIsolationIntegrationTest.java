package com.spendanalyzer.ledger;

import static com.spendanalyzer.ledger.LedgerApi.body;
import static com.spendanalyzer.ledger.LedgerApi.expense;
import static com.spendanalyzer.ledger.LedgerApi.income;
import static com.spendanalyzer.ledger.LedgerApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** One user can never read or change another user's money, and the export holds only own data. */
@IntegrationTest
class LedgerIsolationIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	LedgerApi api;

	@BeforeEach
	void setUp() {
		api = new LedgerApi(mvc);
	}

	@Test
	void userBGets404OnUserAsEntryAndChangesNothing() throws Exception {
		AuthUser alice = TestData.createUser(jdbc);
		AuthUser bob = TestData.createUser(jdbc);
		api.onboard(alice, "5000", "500", "0");
		api.onboard(bob, "70", "7", "0");
		String aliceEntry = api.created(alice, expense("alice's secret spend", "40", "UPI"));
		Map<String, Object> before = read(api.entry(alice, aliceEntry), "$.entry");

		// read, patch, delete: all "no such entry", exactly like an id that does not exist
		api.entry(bob, aliceEntry).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
		api.edit(bob, aliceEntry, body("amount", "1", "description", "hacked", "category", "OTHER"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("not_found"));
		api.remove(bob, aliceEntry).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));

		// Alice's entry and both users' balances are untouched
		assertThat(LedgerApi.<Map<String, Object>>read(api.entry(alice, aliceEntry).andExpect(status().isOk()), "$.entry"))
			.isEqualTo(before);
		api.assertBalances(alice, "4960.00", "500.00", "0.00");
		api.assertBalances(bob, "70.00", "7.00", "0.00");
	}

	@Test
	void userBNeverSeesUserAsEntriesOrBalances() throws Exception {
		AuthUser alice = TestData.createUser(jdbc);
		AuthUser bob = TestData.createUser(jdbc);
		api.onboard(alice, "5000", "500", "250");
		api.created(alice, expense("alice lunch", "120", "CASH"));
		api.created(alice, income("900", "BANK"));

		api.list(bob, "")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.items").isEmpty());
		api.list(bob, "?q=alice").andExpect(jsonPath("$.total").value(0));
		api.balances(bob)
			.andExpect(jsonPath("$.bank").value("0.00"))
			.andExpect(jsonPath("$.cash").value("0.00"))
			.andExpect(jsonPath("$.creditCardDue").value("0.00"))
			.andExpect(jsonPath("$.onboarded").value(false));

		// Bob's own activity lands only on Bob
		api.created(bob, expense("bob tea", "10", "CASH"));
		api.list(bob, "").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].description").value("bob tea"));
		api.assertBalances(bob, "0.00", "-10.00", "0.00");
		api.assertBalances(alice, "5900.00", "380.00", "250.00");
		api.list(alice, "?q=bob").andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void theClientIdIsPerUserSoItCannotBeUsedToProbeAnotherUser() throws Exception {
		AuthUser alice = TestData.createUser(jdbc);
		AuthUser bob = TestData.createUser(jdbc);
		Map<String, Object> request = expense("alice lunch", "120", "CASH");
		String aliceEntry = api.created(alice, request);

		// same clientId from Bob: a new entry of his own, never Alice's entry back
		ResultActions result = api.create(bob, request).andExpect(status().isCreated());
		assertThat(LedgerApi.<String>read(result, "$.entry.id")).isNotEqualTo(aliceEntry);
		api.assertBalances(alice, "0.00", "-120.00", "0.00");
		api.assertBalances(bob, "0.00", "-120.00", "0.00");
	}

	@Test
	void exportIsADownloadOfOnlyTheUsersOwnData() throws Exception {
		AuthUser alice = TestData.createUser(jdbc);
		AuthUser bob = TestData.createUser(jdbc);
		api.onboard(alice, "5000", "500", "0");
		api.created(alice, expense("alice lunch", "120", "CASH"));
		api.created(alice, expense("alice <b>bus</b>", "20", "UPI"));
		api.onboard(bob, "1", "2", "3");
		api.created(bob, expense("bob tea", "10", "CASH"));

		ResultActions result = mvc.perform(get("/api/export").with(TestData.as(alice))).andExpect(status().isOk());
		var response = result.andReturn().getResponse();
		assertThat(response.getHeader("Content-Disposition")).startsWith("attachment").contains(".json");
		assertThat(response.getHeader("Cache-Control")).contains("no-store");
		assertThat(response.getContentType()).startsWith("application/json");

		Map<String, Object> export = read(result, "$");
		assertThat(export).containsOnlyKeys("exportedAt", "user", "balances", "entries");
		assertThat(Instant.parse((String) export.get("exportedAt"))).isBeforeOrEqualTo(Instant.now().plusSeconds(1));

		Map<String, Object> user = read(result, "$.user");
		assertThat(user).containsOnlyKeys("name", "email", "phoneMasked");
		assertThat(user).containsEntry("name", alice.name()).containsEntry("email", alice.email());
		// TestData gives every user the phone +919876543210
		assertThat(user).containsEntry("phoneMasked", "+91 ••••••3210");
		assertThat(LedgerApi.text(result)).doesNotContain("9876543210").doesNotContain("not-a-real-hash");

		Map<String, Object> balances = read(result, "$.balances");
		assertThat(balances).containsEntry("bank", "4980.00").containsEntry("cash", "380.00").containsEntry("onboarded", true);

		List<String> descriptions = read(result, "$.entries[*].description");
		assertThat(descriptions).containsExactlyInAnyOrder("alice lunch", "alice <b>bus</b>", "Opening balance",
				"Opening balance");
		List<Map<String, Object>> entries = read(result, "$.entries");
		assertThat(entries).allSatisfy(entry -> assertThat(entry).hasSize(15));
		assertThat(LedgerApi.text(result)).doesNotContain("bob tea").doesNotContain(bob.email());

		// Bob's export is Bob's
		ResultActions bobs = mvc.perform(get("/api/export").with(TestData.as(bob))).andExpect(status().isOk());
		assertThat(LedgerApi.text(bobs)).contains("bob tea").doesNotContain("alice");
		assertThat(LedgerApi.<String>read(bobs, "$.user.email")).isEqualTo(bob.email());

		mvc.perform(get("/api/export")).andExpect(status().isUnauthorized());
	}

	@Test
	void exportOfAUserWithoutAnyDataWorks() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		mvc.perform(get("/api/export").with(TestData.as(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entries").isEmpty())
			.andExpect(jsonPath("$.balances.bank").value("0.00"))
			.andExpect(jsonPath("$.balances.onboarded").value(false));
	}

}
