package com.spendanalyzer.ledger;

import static com.spendanalyzer.ledger.LedgerApi.adjustment;
import static com.spendanalyzer.ledger.LedgerApi.body;
import static com.spendanalyzer.ledger.LedgerApi.expense;
import static com.spendanalyzer.ledger.LedgerApi.income;
import static com.spendanalyzer.ledger.LedgerApi.withdrawal;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Overlapping requests (double click, two tabs, a retry) must never corrupt a balance. */
@IntegrationTest
class LedgerConcurrencyIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	LedgerApi api;

	@BeforeEach
	void setUp() {
		api = new LedgerApi(mvc);
	}

	/** Starts all calls at the same moment on their own threads and returns their HTTP statuses. */
	private static List<Integer> together(List<Callable<Integer>> calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.size());
		CountDownLatch ready = new CountDownLatch(calls.size());
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> call : calls) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return call.call();
				}));
			}
			assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(120, TimeUnit.SECONDS));
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static int status(org.springframework.test.web.servlet.ResultActions result) {
		return result.andReturn().getResponse().getStatus();
	}

	private long count(AuthUser user, String type) {
		return jdbc.sql("SELECT count(*) FROM entries WHERE user_id = ? AND type = ?")
			.params(user.id(), type)
			.query(Long.class)
			.single();
	}

	@Test
	void thirtyParallelSpendsLeaveTheBalanceExact() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "10000", "0", "0");

		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			Map<String, Object> request = expense("spend " + i, "33.33", "UPI");
			calls.add(() -> status(api.create(user, request)));
		}
		assertThat(together(calls)).hasSize(30).containsOnly(201);

		// 10000 - 30 x 33.33 = 9000.10: one lost update would show up here
		api.assertBalances(user, "9000.10", "0.00", "0.00");
		assertThat(count(user, "EXPENSE")).isEqualTo(30);
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);
	}

	@Test
	void parallelSpendsOfABrandNewUserCreateTheAccountsOnce() throws Exception {
		// no GET /api/balances and no onboarding first: the account rows do not exist yet
		AuthUser user = TestData.createUser(jdbc);
		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			Map<String, Object> request = expense("spend " + i, "10", i % 2 == 0 ? "CASH" : "CREDIT_CARD");
			calls.add(() -> status(api.create(user, request)));
		}
		assertThat(together(calls)).containsOnly(201);
		api.assertBalances(user, "0.00", "-60.00", "60.00");
		Long accounts = jdbc.sql("SELECT count(*) FROM accounts WHERE user_id = ?").param(user.id()).query(Long.class).single();
		assertThat(accounts).isEqualTo(3);
	}

	@Test
	void twoParallelPostsWithTheSameClientIdCreateOneEntry() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "1000", "0", "0");
		Map<String, Object> request = expense("juse", "40", "UPI");

		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			calls.add(() -> status(api.create(user, request)));
		}
		List<Integer> statuses = together(calls);

		// exactly one request created the entry, the others were answered with it
		assertThat(statuses).containsOnly(201, 200);
		assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
		assertThat(count(user, "EXPENSE")).isEqualTo(1);
		api.assertBalances(user, "960.00", "0.00", "0.00");
	}

	@Test
	void sameClientIdOnDifferentAccountsStillCreatesOneEntry() throws Exception {
		// The two requests do not block each other on a balance row, so this goes through the
		// "lost the insert race" path.
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "1000", "1000", "0");
		for (int round = 0; round < 5; round++) {
			Map<String, Object> viaBank = expense("juse", "40", "UPI");
			Map<String, Object> viaCash = expense("juse", "40", "CASH");
			viaCash.put("clientId", viaBank.get("clientId"));
			List<Integer> statuses = together(List.of(() -> status(api.create(user, viaBank)),
					() -> status(api.create(user, viaCash))));
			assertThat(statuses).containsExactlyInAnyOrder(201, 200);
		}
		assertThat(count(user, "EXPENSE")).isEqualTo(5);
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);
		// 5 x 40 left bank and cash together, whichever request won each round
		Map<String, Object> shown = LedgerApi.read(api.balances(user), "$");
		assertThat(shown).containsEntry("available", "1800.00");
	}

	@Test
	void twoParallelAdjustmentsWithTheSameClientIdAreOneAdjustment() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "1000", "0", "0");
		Map<String, Object> request = adjustment("BANK", "750");
		List<Integer> statuses = together(List.of(() -> status(api.create(user, request)),
				() -> status(api.create(user, request)), () -> status(api.create(user, request))));
		// the losers get the original entry back, not "nothing to change"
		assertThat(statuses).containsExactlyInAnyOrder(201, 200, 200);
		api.assertBalances(user, "750.00", "0.00", "0.00");
		assertThat(count(user, "ADJUSTMENT")).isEqualTo(2); // the opening entry and this one
	}

	@Test
	void twoParallelDeletesOfOneEntryRefundOnce() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "1000", "0", "0");
		for (int round = 0; round < 5; round++) {
			String id = api.created(user, expense("juse", "40", "UPI"));
			api.assertBalances(user, "960.00", "0.00", "0.00");

			List<Integer> statuses = together(
					List.of(() -> status(api.remove(user, id)), () -> status(api.remove(user, id))));

			assertThat(statuses).containsExactlyInAnyOrder(200, 404);
			api.assertBalances(user, "1000.00", "0.00", "0.00");
		}
		assertThat(count(user, "EXPENSE")).isZero();
	}

	@Test
	void twoParallelOpeningSubmitsSetTheBalancesOnce() throws Exception {
		for (int round = 0; round < 5; round++) {
			AuthUser user = TestData.createUser(jdbc);
			Map<String, Object> request = body("bank", "5200", "cash", "340", "creditCardDue", "150");

			List<Integer> statuses = together(
					List.of(() -> status(api.opening(user, request)), () -> status(api.opening(user, request))));

			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
			api.assertBalances(user, "5200.00", "340.00", "150.00");
			assertThat(count(user, "ADJUSTMENT")).isEqualTo(3);
			LedgerApi.assertLedgerExplainsBalances(jdbc, user);
		}
	}

	@Test
	void parallelEditsOfOneEntryKeepTheLedgerConsistent() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "1000", "1000", "0");
		String id = api.created(user, expense("juse", "40", "UPI"));
		String[] modes = { "UPI", "CASH", "CREDIT_CARD", "DEBIT_CARD" };

		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			Map<String, Object> change = body("amount", String.valueOf(10 + i), "paymentMode", modes[i % 4]);
			calls.add(() -> status(api.edit(user, id, change)));
		}
		assertThat(together(calls)).containsOnly(200);
		// whichever edit came last, the balances match the one entry that is left
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);
	}

	@Test
	void aMixOfParallelWritesKeepsEveryBalanceEqualToItsEntries() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "50000", "5000", "2000");
		List<String> existing = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			existing.add(api.created(user, expense("old " + i, "25", i % 2 == 0 ? "UPI" : "CASH")));
		}

		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			String id = existing.get(i);
			Map<String, Object> spend = expense("new " + i, "12.50", i % 2 == 0 ? "CREDIT_CARD" : "DEBIT_CARD");
			Map<String, Object> move = i % 2 == 0 ? withdrawal("100") : body("type", "CARD_PAYMENT", "amount", "75", "account", "CASH");
			calls.add(() -> status(api.create(user, spend)));
			calls.add(() -> status(api.create(user, move)));
			calls.add(() -> status(api.create(user, income("10", "BANK"))));
			calls.add(i < 4 ? () -> status(api.remove(user, id))
					: () -> status(api.edit(user, id, body("amount", "99", "paymentMode", "CREDIT_CARD"))));
		}
		List<Integer> statuses = together(calls);

		assertThat(statuses).containsOnly(201, 200);
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);
	}

}
