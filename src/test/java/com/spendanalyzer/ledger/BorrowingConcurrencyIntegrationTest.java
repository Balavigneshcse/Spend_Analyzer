package com.spendanalyzer.ledger;

import static com.spendanalyzer.ledger.LedgerApi.body;
import static com.spendanalyzer.ledger.LedgerApi.borrow;
import static com.spendanalyzer.ledger.LedgerApi.expense;
import static com.spendanalyzer.ledger.LedgerApi.income;
import static com.spendanalyzer.ledger.LedgerApi.repay;
import static com.spendanalyzer.ledger.LedgerApi.withdrawal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Overlapping borrow, pay-back and delete requests: no friend is ever owed less than nothing, and
 * every balance still equals the sum of its entries.
 */
@IntegrationTest
class BorrowingConcurrencyIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	LedgerApi api;

	@BeforeEach
	void setUp() {
		api = new LedgerApi(mvc);
	}

	/** Starts all calls at the same moment on their own threads and returns their HTTP statuses, in the order given. */
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

	private static int status(ResultActions result) {
		return result.andReturn().getResponse().getStatus();
	}

	private long count(AuthUser user, String type) {
		return jdbc.sql("SELECT count(*) FROM entries WHERE user_id = ? AND type = ?")
			.params(user.id(), type)
			.query(Long.class)
			.single();
	}

	/** Both invariants, and the API shows exactly the debt the entries add up to. */
	private BigDecimal assertConsistent(AuthUser user) throws Exception {
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);
		BigDecimal owed = LedgerApi.assertNoFriendIsOwedLessThanNothing(jdbc, user);
		assertThat(new BigDecimal(LedgerApi.<String>read(api.balances(user), "$.owedToFriends"))).isEqualByComparingTo(owed);
		assertThat(new BigDecimal(LedgerApi.<String>read(api.debts(user), "$.total"))).isEqualByComparingTo(owed);
		return owed;
	}

	@Test
	void twentyParallelPayBacksThatTogetherExceedTheDebtNeverTakeItBelowZero() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "5000", "0", "0");
		api.created(user, borrow("Ravi", "1000", "BANK"));

		// 20 x 70 = 1400 against a debt of 1000
		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			Map<String, Object> request = repay(i % 2 == 0 ? "Ravi" : " RAVI ", "70", "BANK");
			calls.add(() -> status(api.create(user, request)));
		}
		List<Integer> statuses = together(calls);

		assertThat(statuses).hasSize(20).containsOnly(201, 400);
		long accepted = statuses.stream().filter(s -> s == 201).count();
		// one after the other, each sees what the ones before left: 14 fit (980), the 15th would not
		assertThat(accepted).isEqualTo(14);
		assertThat(count(user, "REPAY")).isEqualTo(14);
		// 5000 + 1000 - 14 x 70: the bank lost exactly the accepted pay-backs
		api.assertBalances(user, "5020.00", "0.00", "0.00");
		assertThat(assertConsistent(user)).isEqualByComparingTo("20.00");
		api.debts(user)
			.andExpect(jsonPath("$.people[0].owed").value("20.00"))
			.andExpect(jsonPath("$.people[0].repaid").value("980.00"));
	}

	@Test
	void parallelPayBacksFromBankAndCashShareOneDebt() throws Exception {
		// The two halves lock different balance rows, so only the debt lock can line them up.
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "5000", "5000", "0");
		for (int round = 0; round < 15; round++) {
			String friend = "Friend " + round;
			api.created(user, borrow(friend, "100", "CASH"));

			// the whole debt, four times at once, two by UPI and two in cash
			List<Callable<Integer>> calls = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				Map<String, Object> request = repay(friend, "100", i % 2 == 0 ? "BANK" : "CASH");
				calls.add(() -> status(api.create(user, request)));
			}
			List<Integer> statuses = together(calls);

			assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(201, 400, 400, 400);
			assertThat(assertConsistent(user)).isEqualByComparingTo("0.00");
		}
		// borrowed 1500 and paid back 1500, whichever of bank and cash paid
		assertThat(LedgerApi.<String>read(api.balances(user), "$.available")).isEqualTo("10000.00");
		assertThat(count(user, "REPAY")).isEqualTo(15);
	}

	@Test
	void parallelPayBacksToDifferentFriendsEachStayWithinTheirOwnDebt() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "5000", "0", "0");
		String[] friends = { "Ravi", "Arun", "Chitra", "Bala" };
		for (String friend : friends) {
			api.created(user, borrow(friend, "150", "BANK"));
		}
		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			Map<String, Object> request = repay(friends[i % 4], "100", "BANK");
			calls.add(() -> status(api.create(user, request)));
		}
		List<Integer> statuses = together(calls);

		// one 100 fits into each 150, the other four attempts per friend do not
		assertThat(statuses.stream().filter(s -> s == 201)).hasSize(4);
		assertThat(statuses.stream().filter(s -> s == 400)).hasSize(16);
		assertThat(assertConsistent(user)).isEqualByComparingTo("200.00");
		api.assertBalances(user, "5200.00", "0.00", "0.00");
	}

	@Test
	void sixParallelPayBacksWithTheSameClientIdAreOnePayBack() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "1000", "0", "0");
		api.created(user, borrow("Ravi", "300", "BANK"));
		// pays everything back: a second copy that got through would be refused or, worse, counted
		Map<String, Object> request = repay("Ravi", "300", "BANK");

		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			calls.add(() -> status(api.create(user, request)));
		}
		List<Integer> statuses = together(calls);

		// exactly one created it; the others got the entry back, not "you owe nothing"
		assertThat(statuses).containsOnly(201, 200);
		assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
		assertThat(count(user, "REPAY")).isEqualTo(1);
		api.assertBalances(user, "1000.00", "0.00", "0.00");
		assertThat(assertConsistent(user)).isEqualByComparingTo("0.00");
	}

	@Test
	void aPayBackAndTheDeleteOfItsBorrowAtTheSameMomentCannotBothWin() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "5000", "0", "0");
		for (int round = 0; round < 8; round++) {
			String friend = "Friend " + round;
			String borrowId = api.created(user, borrow(friend, "500", "BANK"));

			List<Integer> statuses = together(List.of(() -> status(api.remove(user, borrowId)),
					() -> status(api.create(user, repay(friend, "500", "BANK")))));

			// Either the borrow went first (then there is nothing to pay back) or the pay-back did
			// (then the borrow can no longer be deleted). Both at once would leave the friend at -500.
			assertThat(statuses).isIn(List.of(200, 400), List.of(409, 201));
			assertThat(assertConsistent(user)).isEqualByComparingTo("0.00");
		}
		api.assertBalances(user, "5000.00", "0.00", "0.00");
	}

	@Test
	void parallelBorrowsPayBacksAndDeletesKeepEveryBalanceEqualToItsEntries() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "50000", "5000", "0");
		for (int round = 0; round < 3; round++) {
			List<String> borrows = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				borrows.add(api.created(user, borrow(i % 2 == 0 ? "Ravi" : "Arun", "100", i % 3 == 0 ? "CASH" : "BANK")));
			}
			List<Callable<Integer>> deletes = new ArrayList<>();
			List<Callable<Integer>> newBorrows = new ArrayList<>();
			List<Callable<Integer>> payBacks = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				String id = borrows.get(i);
				String friend = i % 2 == 0 ? "Ravi" : "Arun";
				Map<String, Object> more = borrow(friend, "50", i % 2 == 0 ? "BANK" : "CASH");
				Map<String, Object> back = repay(friend, "120", i % 2 == 0 ? "CASH" : "BANK");
				deletes.add(() -> status(api.remove(user, id)));
				newBorrows.add(() -> status(api.create(user, more)));
				payBacks.add(() -> status(api.create(user, back)));
			}
			List<Callable<Integer>> calls = new ArrayList<>();
			calls.addAll(deletes);
			calls.addAll(newBorrows);
			calls.addAll(payBacks);
			List<Integer> statuses = together(calls);

			// a delete is done or refused because of a pay-back; a borrow always works; a pay-back fits or not
			assertThat(statuses.subList(0, 6)).isSubsetOf(200, 409);
			assertThat(statuses.subList(6, 12)).containsOnly(201);
			assertThat(statuses.subList(12, 18)).isSubsetOf(201, 400);
			assertConsistent(user);
		}
	}

	@Test
	void debtRequestsMixedWithEveryOtherKindOfWriteNeverDeadlock() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "50000", "5000", "2000");
		List<String> spends = new ArrayList<>();
		List<String> borrows = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			spends.add(api.created(user, expense("old " + i, "25", i % 2 == 0 ? "UPI" : "CASH")));
			borrows.add(api.created(user, borrow("Ravi", "200", i % 2 == 0 ? "BANK" : "CASH")));
		}

		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			String spend = spends.get(i);
			String borrowed = borrows.get(i);
			Map<String, Object> newSpend = expense("new " + i, "12.50", i % 2 == 0 ? "CASH" : "DEBIT_CARD");
			Map<String, Object> move = i % 2 == 0 ? withdrawal("100")
					: body("type", "CARD_PAYMENT", "amount", "75", "account", "CASH");
			Map<String, Object> back = repay("Ravi", "150", i % 2 == 0 ? "CASH" : "BANK");
			Map<String, Object> more = borrow("Arun", "40", i % 2 == 0 ? "BANK" : "CASH");
			calls.add(() -> status(api.create(user, newSpend)));
			calls.add(() -> status(api.create(user, move)));
			calls.add(() -> status(api.create(user, income("10", "BANK"))));
			calls.add(() -> status(api.create(user, back)));
			calls.add(() -> status(api.create(user, more)));
			calls.add(() -> status(api.remove(user, borrowed)));
			calls.add(i < 3 ? () -> status(api.remove(user, spend))
					: () -> status(api.edit(user, spend, body("amount", "99", "paymentMode", "CREDIT_CARD"))));
		}
		List<Integer> statuses = together(calls);

		// a deadlock would surface as a 500; 400 and 409 are the debt rules saying no
		assertThat(statuses).isSubsetOf(201, 200, 400, 409);
		assertConsistent(user);
	}

}
