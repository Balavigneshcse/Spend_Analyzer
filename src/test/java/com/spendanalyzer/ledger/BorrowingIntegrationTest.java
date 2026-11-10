package com.spendanalyzer.ledger;

import static com.spendanalyzer.ledger.LedgerApi.adjustment;
import static com.spendanalyzer.ledger.LedgerApi.body;
import static com.spendanalyzer.ledger.LedgerApi.borrow;
import static com.spendanalyzer.ledger.LedgerApi.expense;
import static com.spendanalyzer.ledger.LedgerApi.income;
import static com.spendanalyzer.ledger.LedgerApi.read;
import static com.spendanalyzer.ledger.LedgerApi.repay;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

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
 * Borrowing from friends and paying back: the money moves through bank or cash, what is owed is
 * kept per friend, and it can never go below zero.
 */
@IntegrationTest
class BorrowingIntegrationTest {

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

	private LocalDate today() {
		return LocalDate.now(clock.withZone(appZone));
	}

	/** A user who starts with bank 5000, cash 500 and no card due. */
	private AuthUser onboardedUser() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "5000", "500", "0");
		return user;
	}

	private void assertOwed(AuthUser user, String owedToFriends) throws Exception {
		api.balances(user).andExpect(status().isOk()).andExpect(jsonPath("$.owedToFriends").value(owedToFriends));
		api.debts(user).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(owedToFriends));
		assertThat(LedgerApi.assertNoFriendIsOwedLessThanNothing(jdbc, user)).isEqualByComparingTo(owedToFriends);
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);
	}

	private long entryCount(AuthUser user) {
		return jdbc.sql("SELECT count(*) FROM entries WHERE user_id = ?").param(user.id()).query(Long.class).single();
	}

	// ---------------------------------------------------------------- borrow

	@Test
	void debtsNeedLogin() throws Exception {
		mvc.perform(get("/api/debts")).andExpect(status().isUnauthorized());
	}

	@Test
	void aUserWhoNeverBorrowedOwesNothing() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.debts(user)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$.total").value("0.00"))
			.andExpect(jsonPath("$.people").isArray())
			.andExpect(jsonPath("$.people").isEmpty())
			.andExpect(jsonPath("$.knownNames").isArray())
			.andExpect(jsonPath("$.knownNames").isEmpty());
		api.balances(user).andExpect(jsonPath("$.owedToFriends").value("0.00"));
	}

	@Test
	void borrowingByUpiRaisesTheBankAndWhatIsOwed() throws Exception {
		AuthUser user = onboardedUser();

		ResultActions result = api.create(user, borrow("Ravi", "1000", "BANK"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.length()").value(16))
			.andExpect(jsonPath("$.entry.id").isString())
			.andExpect(jsonPath("$.entry.type").value("BORROW"))
			.andExpect(jsonPath("$.entry.amount").value("1000.00"))
			.andExpect(jsonPath("$.entry.account").value("BANK"))
			.andExpect(jsonPath("$.entry.person").value("Ravi"))
			.andExpect(jsonPath("$.entry.paymentMode").value(nullValue()))
			.andExpect(jsonPath("$.entry.description").value(nullValue()))
			.andExpect(jsonPath("$.entry.category").value(nullValue()))
			.andExpect(jsonPath("$.entry.categoryLabel").value(nullValue()))
			.andExpect(jsonPath("$.entry.item").value(nullValue()))
			.andExpect(jsonPath("$.entry.aiConfidence").value(nullValue()))
			.andExpect(jsonPath("$.entry.aiSource").value(nullValue()))
			.andExpect(jsonPath("$.entry.needsReview").value(false))
			.andExpect(jsonPath("$.entry.userCorrected").value(false))
			.andExpect(jsonPath("$.entry.date").value(today().toString()))
			.andExpect(jsonPath("$.balances.length()").value(6))
			.andExpect(jsonPath("$.balances.bank").value("6000.00"))
			.andExpect(jsonPath("$.balances.cash").value("500.00"))
			.andExpect(jsonPath("$.balances.creditCardDue").value("0.00"))
			// the borrowed money is money in the bank; that it is owed is said separately
			.andExpect(jsonPath("$.balances.available").value("6500.00"))
			.andExpect(jsonPath("$.balances.owedToFriends").value("1000.00"))
			.andExpect(jsonPath("$.balances.onboarded").value(true));

		// a later read gives exactly the same entry
		String id = read(result, "$.entry.id");
		Map<String, Object> created = read(result, "$.entry");
		Map<String, Object> fetched = read(api.entry(user, id).andExpect(status().isOk()), "$.entry");
		assertThat(fetched).isEqualTo(created);

		api.balances(user)
			.andExpect(jsonPath("$.bank").value("6000.00"))
			.andExpect(jsonPath("$.owedToFriends").value("1000.00"));
		api.debts(user)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$.total").value("1000.00"))
			.andExpect(jsonPath("$.people.length()").value(1))
			.andExpect(jsonPath("$.people[0].length()").value(5))
			.andExpect(jsonPath("$.people[0].name").value("Ravi"))
			.andExpect(jsonPath("$.people[0].owed").value("1000.00"))
			.andExpect(jsonPath("$.people[0].borrowed").value("1000.00"))
			.andExpect(jsonPath("$.people[0].repaid").value("0.00"))
			.andExpect(jsonPath("$.people[0].lastDate").value(today().toString()))
			.andExpect(jsonPath("$.knownNames.length()").value(1))
			.andExpect(jsonPath("$.knownNames[0]").value("Ravi"));

		// stored as a BORROW row with the name and its key, nothing of an expense
		Map<String, Object> row = jdbc
			.sql("SELECT type, account, amount::text AS amount, person, person_key, payment_mode, category FROM entries WHERE id = ?")
			.param(UUID.fromString(id))
			.query()
			.singleRow();
		assertThat(row).containsEntry("type", "BORROW")
			.containsEntry("account", "BANK")
			.containsEntry("amount", "1000.00")
			.containsEntry("person", "Ravi")
			.containsEntry("person_key", "ravi")
			.containsEntry("payment_mode", null)
			.containsEntry("category", null);
		assertOwed(user, "1000.00");
	}

	@Test
	void borrowingInCashRaisesCashAndKeepsTheNoteAndTheDate() throws Exception {
		AuthUser user = onboardedUser();
		String yesterday = today().minusDays(1).toString();
		Map<String, Object> request = borrow("Arun", "250.50", "CASH");
		request.put("description", "  for the trip ");
		request.put("date", yesterday);

		api.create(user, request)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.type").value("BORROW"))
			.andExpect(jsonPath("$.entry.account").value("CASH"))
			.andExpect(jsonPath("$.entry.person").value("Arun"))
			.andExpect(jsonPath("$.entry.description").value("for the trip"))
			.andExpect(jsonPath("$.entry.date").value(yesterday))
			.andExpect(jsonPath("$.balances.bank").value("5000.00"))
			.andExpect(jsonPath("$.balances.cash").value("750.50"))
			.andExpect(jsonPath("$.balances.available").value("5750.50"))
			.andExpect(jsonPath("$.balances.owedToFriends").value("250.50"));
		api.debts(user)
			.andExpect(jsonPath("$.total").value("250.50"))
			.andExpect(jsonPath("$.people[0].name").value("Arun"))
			.andExpect(jsonPath("$.people[0].owed").value("250.50"))
			.andExpect(jsonPath("$.people[0].lastDate").value(yesterday));
		assertOwed(user, "250.50");
	}

	@Test
	void aBrandNewUserCanBorrowBeforeAnythingElse() throws Exception {
		// no opening balances, no account rows yet
		AuthUser user = TestData.createUser(jdbc);
		api.create(user, borrow("Ravi", "300", "CASH"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.balances.cash").value("300.00"))
			.andExpect(jsonPath("$.balances.owedToFriends").value("300.00"))
			.andExpect(jsonPath("$.balances.onboarded").value(false));
		assertOwed(user, "300.00");
	}

	@Test
	void theNameMayBeFortyCharactersAndIsCleaned() throws Exception {
		AuthUser user = onboardedUser();
		String longest = "x".repeat(40);
		api.create(user, borrow(longest, "10", "CASH"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.person").value(longest));
		// control characters are removed, tabs, line breaks and runs of spaces become one space
		api.create(user, borrow(" Ra\u0000vi\u0007 \t\n  Kumar  ", "10", "CASH"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.person").value("Ravi Kumar"));
		// other scripts and emoji are names like any other; an emoji counts as one character
		api.create(user, borrow("ரவி 😀", "10", "CASH"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.person").value("ரவி 😀"));
		api.create(user, borrow("😀".repeat(40), "10", "CASH")).andExpect(status().isCreated());
		api.debts(user).andExpect(jsonPath("$.people.length()").value(4)).andExpect(jsonPath("$.total").value("40.00"));
	}

	// ---------------------------------------------------------------- pay back

	@Test
	void payingBackPartAndThenAllTakesTheFriendOffTheListButKeepsTheName() throws Exception {
		AuthUser user = onboardedUser();
		api.created(user, borrow("Ravi", "1000", "BANK"));
		Map<String, Object> fromArun = borrow("Arun", "500", "CASH");
		fromArun.put("date", today().minusDays(3).toString());
		api.created(user, fromArun);
		api.assertBalances(user, "6000.00", "1000.00", "0.00");
		assertOwed(user, "1500.00");

		// part of it, in cash although it came by UPI
		api.create(user, repay("Ravi", "400", "CASH"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.length()").value(16))
			.andExpect(jsonPath("$.entry.type").value("REPAY"))
			.andExpect(jsonPath("$.entry.amount").value("400.00"))
			.andExpect(jsonPath("$.entry.account").value("CASH"))
			.andExpect(jsonPath("$.entry.person").value("Ravi"))
			.andExpect(jsonPath("$.entry.paymentMode").value(nullValue()))
			.andExpect(jsonPath("$.entry.category").value(nullValue()))
			.andExpect(jsonPath("$.balances.bank").value("6000.00"))
			.andExpect(jsonPath("$.balances.cash").value("600.00"))
			.andExpect(jsonPath("$.balances.available").value("6600.00"))
			.andExpect(jsonPath("$.balances.owedToFriends").value("1100.00"));
		api.debts(user)
			.andExpect(jsonPath("$.total").value("1100.00"))
			.andExpect(jsonPath("$.people.length()").value(2))
			.andExpect(jsonPath("$.people[0].name").value("Ravi"))
			.andExpect(jsonPath("$.people[0].owed").value("600.00"))
			.andExpect(jsonPath("$.people[0].borrowed").value("1000.00"))
			.andExpect(jsonPath("$.people[0].repaid").value("400.00"))
			.andExpect(jsonPath("$.people[0].lastDate").value(today().toString()))
			.andExpect(jsonPath("$.people[1].name").value("Arun"))
			.andExpect(jsonPath("$.people[1].owed").value("500.00"))
			.andExpect(jsonPath("$.people[1].borrowed").value("500.00"))
			.andExpect(jsonPath("$.people[1].repaid").value("0.00"))
			.andExpect(jsonPath("$.people[1].lastDate").value(today().minusDays(3).toString()));

		// the rest, exactly what is owed, by UPI
		api.create(user, repay("Ravi", "600", "BANK"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.balances.bank").value("5400.00"))
			.andExpect(jsonPath("$.balances.owedToFriends").value("500.00"));
		// Ravi is paid back: off the list, still a known name
		api.debts(user)
			.andExpect(jsonPath("$.total").value("500.00"))
			.andExpect(jsonPath("$.people.length()").value(1))
			.andExpect(jsonPath("$.people[0].name").value("Arun"))
			.andExpect(jsonPath("$.knownNames.length()").value(2))
			.andExpect(jsonPath("$.knownNames[0]").value("Arun"))
			.andExpect(jsonPath("$.knownNames[1]").value("Ravi"));
		assertOwed(user, "500.00");

		// and one can borrow from Ravi again
		api.created(user, borrow("Ravi", "50", "CASH"));
		api.debts(user)
			.andExpect(jsonPath("$.people.length()").value(2))
			.andExpect(jsonPath("$.people[1].name").value("Ravi"))
			.andExpect(jsonPath("$.people[1].owed").value("50.00"))
			.andExpect(jsonPath("$.people[1].borrowed").value("1050.00"))
			.andExpect(jsonPath("$.people[1].repaid").value("1000.00"));
		assertOwed(user, "550.00");
	}

	@Test
	void payingBackMoreThanIsOwedIsRefusedAndSaysWhatIsOwed() throws Exception {
		AuthUser user = onboardedUser();
		api.created(user, borrow("Ravi", "500", "BANK"));
		long entries = entryCount(user);

		api.create(user, repay("Ravi", "500.01", "BANK"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation"))
			.andExpect(jsonPath("$.message").value("You owe Ravi only ₹500.00."))
			.andExpect(jsonPath("$.fields.length()").value(1))
			.andExpect(jsonPath("$.fields.amount").value("You owe Ravi only ₹500.00."));
		api.create(user, repay("ravi", "1000000", "CASH"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.amount").value("You owe Ravi only ₹500.00."));
		// nothing moved
		assertThat(entryCount(user)).isEqualTo(entries);
		api.assertBalances(user, "5500.00", "500.00", "0.00");
		assertOwed(user, "500.00");

		// after a part payment the limit is what is left
		api.created(user, repay("Ravi", "200", "BANK"));
		api.create(user, repay("Ravi", "300.01", "BANK"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.amount").value("You owe Ravi only ₹300.00."));
		api.create(user, repay("Ravi", "300", "BANK")).andExpect(status().isCreated());
		api.assertBalances(user, "5000.00", "500.00", "0.00");
		assertOwed(user, "0.00");
	}

	@Test
	void payingBackSomeoneWhoIsOwedNothingIsRefused() throws Exception {
		AuthUser user = onboardedUser();
		// never borrowed from this person
		api.create(user, repay("Nobody", "100", "BANK"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation"))
			.andExpect(jsonPath("$.message").value("You have not borrowed anything from Nobody."))
			.andExpect(jsonPath("$.fields.length()").value(1))
			.andExpect(jsonPath("$.fields.person").value("You have not borrowed anything from Nobody."));
		assertThat(entryCount(user)).isEqualTo(2); // only the opening entries
		api.debts(user).andExpect(jsonPath("$.knownNames").isEmpty());

		// borrowed and paid back in full: nothing left to pay
		api.created(user, borrow("Ravi", "100", "CASH"));
		api.created(user, repay("Ravi", "100", "CASH"));
		api.create(user, repay("RAVI", "0.01", "CASH"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.person").value("You do not owe Ravi anything."));
		// owing one friend does not make a pay-back to another one possible
		api.created(user, borrow("Arun", "900", "BANK"));
		api.create(user, repay("Ravi", "100", "BANK"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.person").exists());
		api.assertBalances(user, "5900.00", "500.00", "0.00");
		assertOwed(user, "900.00");
	}

	// ---------------------------------------------------------------- one friend, many spellings

	@Test
	void theSameFriendInAnotherSpellingIsOneFriendAndKeepsTheFirstSpelling() throws Exception {
		AuthUser user = onboardedUser();
		String first = api.created(user, borrow("Ravi Kumar", "500", "BANK"));
		api.create(user, borrow("  ravi   KUMAR ", "200", "CASH"))
			.andExpect(status().isCreated())
			// the entry keeps what was typed (cleaned) ...
			.andExpect(jsonPath("$.entry.person").value("ravi KUMAR"))
			.andExpect(jsonPath("$.balances.owedToFriends").value("700.00"));
		api.create(user, repay("RAVI\tKUMAR", "100", "CASH"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.person").value("RAVI KUMAR"));

		// ... and the friend is one person, shown in the first spelling
		api.debts(user)
			.andExpect(jsonPath("$.total").value("600.00"))
			.andExpect(jsonPath("$.people.length()").value(1))
			.andExpect(jsonPath("$.people[0].name").value("Ravi Kumar"))
			.andExpect(jsonPath("$.people[0].owed").value("600.00"))
			.andExpect(jsonPath("$.people[0].borrowed").value("700.00"))
			.andExpect(jsonPath("$.people[0].repaid").value("100.00"))
			.andExpect(jsonPath("$.knownNames.length()").value(1))
			.andExpect(jsonPath("$.knownNames[0]").value("Ravi Kumar"));
		List<String> keys = jdbc.sql("SELECT DISTINCT person_key FROM entries WHERE user_id = ? AND person_key IS NOT NULL")
			.param(user.id())
			.query(String.class)
			.list();
		assertThat(keys).containsExactly("ravi kumar");
		// messages use the first spelling too, whatever was typed this time
		api.create(user, repay("ravi kumar", "999", "CASH"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.amount").value("You owe Ravi Kumar only ₹600.00."));

		// "Ravi" alone is somebody else
		api.created(user, borrow("Ravi", "50", "CASH"));
		api.debts(user).andExpect(jsonPath("$.people.length()").value(2));

		// when the first entry goes, the oldest one that is left gives the name
		api.remove(user, first).andExpect(status().isOk());
		api.debts(user)
			.andExpect(jsonPath("$.people[0].name").value("ravi KUMAR"))
			.andExpect(jsonPath("$.people[0].owed").value("100.00"));
		assertOwed(user, "150.00");
	}

	@Test
	void peopleAreSortedByDebtThenNameAndKnownNamesWithoutRegardToCase() throws Exception {
		AuthUser user = onboardedUser();
		api.created(user, borrow("bala", "300", "CASH"));
		api.created(user, borrow("Arun", "300", "CASH"));
		api.created(user, borrow("Chitra", "900", "BANK"));
		api.created(user, borrow("arjun", "100", "CASH"));
		api.created(user, borrow("Zoya", "40", "CASH"));
		api.created(user, repay("Zoya", "40", "CASH"));

		ResultActions debts = api.debts(user).andExpect(status().isOk()).andExpect(jsonPath("$.total").value("1600.00"));
		assertThat(LedgerApi.<List<String>>read(debts, "$.people[*].name")).containsExactly("Chitra", "Arun", "bala", "arjun");
		assertThat(LedgerApi.<List<String>>read(debts, "$.people[*].owed")).containsExactly("900.00", "300.00", "300.00",
				"100.00");
		// every friend ever used, also the one who is paid back; "arjun" sorts before "Arun"
		assertThat(LedgerApi.<List<String>>read(debts, "$.knownNames")).containsExactly("arjun", "Arun", "bala", "Chitra",
				"Zoya");
	}

	@Test
	void knownNamesStopAtTwoHundredAndKeepTheMostRecentlyUsed() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		// 205 friends straight into the table, friend 000 the oldest
		for (int i = 0; i < 205; i++) {
			String name = String.format("Friend %03d", i);
			jdbc.sql("""
					INSERT INTO entries (id, user_id, client_id, type, amount, account, person, person_key, entry_date, created_at)
					VALUES (?, ?, ?, 'BORROW', 1, 'CASH', ?, ?, ?, now() - make_interval(secs => ?))
					""").params(UUID.randomUUID(), user.id(), UUID.randomUUID(), name, name.toLowerCase(), today(), 1000 - i).update();
		}
		ResultActions debts = api.debts(user).andExpect(status().isOk()).andExpect(jsonPath("$.total").value("205.00"));
		List<String> names = read(debts, "$.knownNames");
		assertThat(names).hasSize(DebtsView.MAX_KNOWN_NAMES).isSorted().startsWith("Friend 005").endsWith("Friend 204");
		// the list of who is owed money is never cut
		assertThat(LedgerApi.<List<String>>read(debts, "$.people[*].name")).hasSize(205);
	}

	// ---------------------------------------------------------------- delete

	@Test
	void deletingABorrowThatWasPartlyPaidBackIsRefusedAndNothingChanges() throws Exception {
		AuthUser user = onboardedUser();
		String borrowId = api.created(user, borrow("Ravi", "1000", "BANK"));
		String repayId = api.created(user, repay("Ravi", "400", "CASH"));
		api.assertBalances(user, "6000.00", "100.00", "0.00");
		Map<String, Object> before = read(api.entry(user, borrowId), "$.entry");
		String debtsBefore = LedgerApi.text(api.debts(user));

		api.remove(user, borrowId)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("conflict"))
			.andExpect(jsonPath("$.message")
				.value("You have already paid Ravi back for this (you owe ₹600.00 now). Delete the pay-back first."))
			.andExpect(jsonPath("$.fields").doesNotExist());

		// the entry, the balances and the debt are exactly as before
		assertThat(LedgerApi.<Map<String, Object>>read(api.entry(user, borrowId).andExpect(status().isOk()), "$.entry"))
			.isEqualTo(before);
		assertThat(LedgerApi.text(api.debts(user))).isEqualTo(debtsBefore);
		api.assertBalances(user, "6000.00", "100.00", "0.00");
		assertOwed(user, "600.00");

		// the way out: delete the pay-back first (the debt goes back up), then the borrow
		api.remove(user, repayId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$.balances.cash").value("500.00"))
			.andExpect(jsonPath("$.balances.owedToFriends").value("1000.00"));
		assertOwed(user, "1000.00");
		api.remove(user, borrowId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balances.bank").value("5000.00"))
			.andExpect(jsonPath("$.balances.owedToFriends").value("0.00"));
		api.assertBalances(user, "5000.00", "500.00", "0.00");
		// no entry with Ravi is left, so the name is gone as well
		api.debts(user).andExpect(jsonPath("$.people").isEmpty()).andExpect(jsonPath("$.knownNames").isEmpty());
		api.remove(user, borrowId).andExpect(status().isNotFound());
		assertOwed(user, "0.00");
	}

	@Test
	void aBorrowCanBeDeletedWhileTheOtherBorrowsStillCoverWhatWasPaidBack() throws Exception {
		AuthUser user = onboardedUser();
		String big = api.created(user, borrow("Ravi", "1000", "BANK"));
		String small = api.created(user, borrow("Ravi", "500", "CASH"));
		api.created(user, repay("Ravi", "600", "BANK"));
		assertOwed(user, "900.00");

		// without the big one Ravi would have been paid 100 too much
		api.remove(user, big).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("conflict"));
		// without the small one 400 is still owed
		api.remove(user, small).andExpect(status().isOk()).andExpect(jsonPath("$.balances.owedToFriends").value("400.00"));
		api.remove(user, big).andExpect(status().isConflict());
		assertOwed(user, "400.00");

		// a debt to somebody else does not help
		api.created(user, borrow("Arun", "5000", "BANK"));
		api.remove(user, big).andExpect(status().isConflict());
		assertOwed(user, "5400.00");
	}

	@Test
	void aBorrowThatIsPaidBackInFullCannotBeDeletedButAnUntouchedOneCan() throws Exception {
		AuthUser user = onboardedUser();
		String paid = api.created(user, borrow("Ravi", "300", "CASH"));
		api.created(user, repay("Ravi", "300", "CASH"));
		api.remove(user, paid)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message")
				.value("You have already paid Ravi back for this (you owe ₹0.00 now). Delete the pay-back first."));

		String untouched = api.created(user, borrow("Arun", "300", "CASH"));
		api.remove(user, untouched).andExpect(status().isOk()).andExpect(jsonPath("$.balances.owedToFriends").value("0.00"));
		api.assertBalances(user, "5000.00", "500.00", "0.00");
		assertOwed(user, "0.00");
	}

	// ---------------------------------------------------------------- same request twice, edits

	@Test
	void theSameClientIdAgainGivesTheFirstEntryBackAndMovesNothing() throws Exception {
		AuthUser user = onboardedUser();
		Map<String, Object> borrowRequest = borrow("Ravi", "1000", "BANK");
		String id = api.created(user, borrowRequest);
		api.create(user, borrowRequest)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.id").value(id))
			.andExpect(jsonPath("$.balances.bank").value("6000.00"))
			.andExpect(jsonPath("$.balances.owedToFriends").value("1000.00"));

		// a pay-back that clears the debt, sent twice: the second answer is the entry, not "you owe nothing"
		Map<String, Object> repayRequest = repay("Ravi", "1000", "BANK");
		String repayId = api.created(user, repayRequest);
		api.create(user, repayRequest)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.id").value(repayId))
			.andExpect(jsonPath("$.entry.type").value("REPAY"))
			.andExpect(jsonPath("$.balances.owedToFriends").value("0.00"));
		// the same id with other content is still the first entry
		Map<String, Object> other = repay("Arun", "5", "BANK");
		other.put("clientId", repayRequest.get("clientId"));
		api.create(user, other).andExpect(status().isOk()).andExpect(jsonPath("$.entry.id").value(repayId));

		assertThat(entryCount(user)).isEqualTo(4); // two opening entries, one borrow, one pay-back
		api.assertBalances(user, "5000.00", "500.00", "0.00");
		assertOwed(user, "0.00");
	}

	@Test
	void borrowAndRepayEntriesCannotBeEdited() throws Exception {
		AuthUser user = onboardedUser();
		String borrowId = api.created(user, borrow("Ravi", "1000", "BANK"));
		String repayId = api.created(user, repay("Ravi", "400", "CASH"));
		for (String id : List.of(borrowId, repayId)) {
			Map<String, Object> before = read(api.entry(user, id), "$.entry");
			for (Map<String, Object> change : List.of(body("amount", "5"), body("description", "changed"),
					body("date", today().minusDays(1).toString()), body("paymentMode", "CASH"), body("category", "OTHER"),
					body())) {
				api.edit(user, id, change)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error").value("validation"))
					.andExpect(jsonPath("$.message").value("Only spends can be edited. Delete this entry and add it again."));
			}
			// the friend is not something a PATCH knows about at all
			api.edit(user, id, body("person", "Arun"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("bad_request"));
			assertThat(LedgerApi.<Map<String, Object>>read(api.entry(user, id), "$.entry")).isEqualTo(before);
		}
		api.assertBalances(user, "6000.00", "100.00", "0.00");
		assertOwed(user, "600.00");
	}

	// ---------------------------------------------------------------- text is data, never markup

	@Test
	void aNameThatLooksLikeMarkupIsStoredRawAndReturnedAsPlainJsonText() throws Exception {
		AuthUser user = onboardedUser();
		String name = "<script>alert(\"x\")</script> & <b>";
		ResultActions result = api.create(user, borrow(name, "500", "BANK")).andExpect(status().isCreated());
		String id = read(result, "$.entry.id");
		assertThat(LedgerApi.<String>read(result, "$.entry.person")).isEqualTo(name);
		assertThat(result.andReturn().getResponse().getContentType()).startsWith("application/json");

		String stored = jdbc.sql("SELECT person FROM entries WHERE id = ?").param(UUID.fromString(id)).query(String.class).single();
		assertThat(stored).isEqualTo(name);

		ResultActions debts = api.debts(user).andExpect(status().isOk());
		assertThat(LedgerApi.<String>read(debts, "$.people[0].name")).isEqualTo(name);
		assertThat(LedgerApi.<String>read(debts, "$.knownNames[0]")).isEqualTo(name);
		assertThat(debts.andReturn().getResponse().getContentType()).startsWith("application/json");
		ResultActions listed = api.list(user, "?type=BORROW").andExpect(status().isOk());
		assertThat(LedgerApi.<String>read(listed, "$.items[0].person")).isEqualTo(name);
		// the name inside a message is the same raw text
		ResultActions refused = api.create(user, repay(name, "501", "BANK")).andExpect(status().isBadRequest());
		assertThat(LedgerApi.<String>read(refused, "$.fields.amount")).isEqualTo("You owe " + name + " only ₹500.00.");
		// not HTML-escaped anywhere on the way
		for (ResultActions answer : List.of(result, debts, listed, refused)) {
			assertThat(LedgerApi.text(answer)).doesNotContain("&lt;").doesNotContain("&amp;").doesNotContain("&quot;");
		}
	}

	// ---------------------------------------------------------------- list, search, export

	@Test
	void theListShowsTheFriendAndTheSearchAlsoLooksAtTheName() throws Exception {
		AuthUser user = onboardedUser();
		Map<String, Object> fromRavi = borrow("Ravi Kumar", "500", "BANK");
		fromRavi.put("description", "trip advance");
		api.created(user, fromRavi);
		api.created(user, repay("Ravi Kumar", "100", "CASH"));
		api.created(user, borrow("100% Arun_", "50", "CASH"));
		api.created(user, expense("ravioli", "120", "UPI"));
		api.created(user, income("900", "BANK"));

		api.list(user, "?type=BORROW")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[0].person").value("100% Arun_"))
			.andExpect(jsonPath("$.items[1].person").value("Ravi Kumar"))
			.andExpect(jsonPath("$.items[1].description").value("trip advance"));
		api.list(user, "?type=REPAY").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].person").value("Ravi Kumar"));
		// entries of other kinds have no friend
		api.list(user, "?type=EXPENSE").andExpect(jsonPath("$.items[0].person").value(nullValue()));
		api.list(user, "?type=INCOME").andExpect(jsonPath("$.items[0].person").value(nullValue()));

		// q finds the friend's name (any case) as well as descriptions
		api.list(user, "?q=RAVI").andExpect(jsonPath("$.total").value(3));
		api.list(user, "?q=ravi kumar").andExpect(jsonPath("$.total").value(2));
		api.list(user, "?q=kumar&type=REPAY").andExpect(jsonPath("$.total").value(1));
		api.list(user, "?q=trip").andExpect(jsonPath("$.total").value(1));
		api.list(user, "?q=nobody").andExpect(jsonPath("$.total").value(0));
		// % and _ in the search are plain characters in a name too
		api.list(user, "?q=100%").andExpect(jsonPath("$.total").value(1));
		api.list(user, "?q=arun_").andExpect(jsonPath("$.total").value(1));
		api.list(user, "?q=%").andExpect(jsonPath("$.total").value(1));
		api.list(user, "?q=_").andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void theExportCarriesTheDebtsAndTheFriendOfEveryEntry() throws Exception {
		AuthUser user = onboardedUser();
		api.created(user, borrow("Ravi", "1000", "BANK"));
		api.created(user, repay("Ravi", "250", "CASH"));
		api.created(user, borrow("Arun", "40", "CASH"));
		api.created(user, repay("Arun", "40", "CASH"));

		ResultActions export = mvc.perform(get("/api/export").with(TestData.as(user))).andExpect(status().isOk());
		Map<String, Object> root = read(export, "$");
		assertThat(root).containsOnlyKeys("exportedAt", "user", "balances", "debts", "entries");
		// the debts block is the GET /api/debts answer
		Map<String, Object> debts = read(export, "$.debts");
		assertThat(debts).isEqualTo(LedgerApi.<Map<String, Object>>read(api.debts(user), "$"));
		assertThat(debts).containsEntry("total", "750.00");
		assertThat(LedgerApi.<List<String>>read(export, "$.debts.people[*].name")).containsExactly("Ravi");
		assertThat(LedgerApi.<List<String>>read(export, "$.debts.knownNames")).containsExactly("Arun", "Ravi");
		assertThat(LedgerApi.<String>read(export, "$.balances.owedToFriends")).isEqualTo("750.00");

		List<Map<String, Object>> entries = read(export, "$.entries");
		assertThat(entries).hasSize(6).allSatisfy(entry -> assertThat(entry).hasSize(16).containsKey("person"));
		assertThat(entries).filteredOn(entry -> entry.get("person") != null)
			.extracting(entry -> entry.get("type") + " " + entry.get("person") + " " + entry.get("amount"))
			.containsExactlyInAnyOrder("BORROW Ravi 1000.00", "REPAY Ravi 250.00", "BORROW Arun 40.00", "REPAY Arun 40.00");
	}

	// ---------------------------------------------------------------- one user's friends are nobody else's

	@Test
	void userBNeverSeesUserAsDebtsOrNames() throws Exception {
		AuthUser alice = onboardedUser();
		AuthUser bob = onboardedUser();
		String aliceBorrow = api.created(alice, borrow("Alice's Secret Friend", "1000", "BANK"));
		String aliceRepay = api.created(alice, repay("Alice's Secret Friend", "100", "CASH"));
		String aliceDebts = LedgerApi.text(api.debts(alice));

		// Bob owes nobody and knows no names
		api.debts(bob)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value("0.00"))
			.andExpect(jsonPath("$.people").isEmpty())
			.andExpect(jsonPath("$.knownNames").isEmpty());
		api.balances(bob).andExpect(jsonPath("$.owedToFriends").value("0.00"));
		api.list(bob, "?q=secret").andExpect(jsonPath("$.total").value(0));
		api.list(bob, "?type=BORROW").andExpect(jsonPath("$.total").value(0));
		assertThat(LedgerApi.text(mvc.perform(get("/api/export").with(TestData.as(bob))))).doesNotContain("Secret");

		// Alice's debt is not Bob's to pay back: to Bob this friend is unknown
		api.create(bob, repay("Alice's Secret Friend", "100", "BANK"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.person").value("You have not borrowed anything from Alice's Secret Friend."));
		// her entries do not exist for him
		for (String id : List.of(aliceBorrow, aliceRepay)) {
			api.entry(bob, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
			api.remove(bob, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
			api.edit(bob, id, body("amount", "1")).andExpect(status().isNotFound());
		}

		// Bob borrowing from a friend of the same name is his own, separate debt
		api.create(bob, borrow("alice's secret friend", "70", "CASH"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.balances.owedToFriends").value("70.00"));
		api.debts(bob)
			.andExpect(jsonPath("$.total").value("70.00"))
			.andExpect(jsonPath("$.people.length()").value(1))
			.andExpect(jsonPath("$.people[0].name").value("alice's secret friend"))
			.andExpect(jsonPath("$.people[0].borrowed").value("70.00"));
		// and he cannot pay back more than his own 70 although Alice owes 900
		api.create(bob, repay("Alice's Secret Friend", "71", "CASH"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.amount").value("You owe alice's secret friend only ₹70.00."));

		// nothing of this reached Alice
		assertThat(LedgerApi.text(api.debts(alice))).isEqualTo(aliceDebts);
		api.assertBalances(alice, "6000.00", "400.00", "0.00");
		api.assertBalances(bob, "5000.00", "570.00", "0.00");
		assertOwed(alice, "900.00");
		assertOwed(bob, "70.00");
	}

	// ---------------------------------------------------------------- next to the rest of the ledger

	@Test
	void otherEntriesDoNotChangeWhatIsOwed() throws Exception {
		AuthUser user = onboardedUser();
		api.created(user, borrow("Ravi", "1000", "BANK"));
		// spending the borrowed money, correcting a balance, getting income: the debt stays
		api.created(user, expense("shoes", "900", "UPI"));
		api.created(user, adjustment("BANK", "100"));
		api.created(user, income("2000", "CASH"));
		api.created(user, body("type", "WITHDRAWAL", "amount", "50"));
		api.assertBalances(user, "50.00", "2550.00", "0.00");
		assertOwed(user, "1000.00");

		// paying back may take a balance below zero, like any other entry (the entered balance can be out of date)
		api.create(user, repay("Ravi", "1000", "BANK"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.balances.bank").value("-950.00"))
			.andExpect(jsonPath("$.balances.owedToFriends").value("0.00"));
		assertOwed(user, "0.00");
	}

	@Test
	void borrowingShowsUpInTheAnalysisAsAnInsightAndNeverAsSpending() throws Exception {
		AuthUser user = onboardedUser();
		api.created(user, borrow("Ravi", "1000", "BANK"));
		api.created(user, borrow("Arun", "500", "CASH"));
		api.created(user, repay("Ravi", "200", "CASH"));

		ResultActions analysis = mvc.perform(get("/api/analysis").param("period", "THIS_MONTH").with(TestData.as(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.spent").value("0.00"))
			.andExpect(jsonPath("$.totals.count").value(0))
			.andExpect(jsonPath("$.byCategory").isEmpty())
			.andExpect(jsonPath("$.byMode").isEmpty());
		List<Map<String, Object>> borrowed = read(analysis, "$.insights[?(@.type == 'BORROWED')]");
		assertThat(borrowed).hasSize(1);
		assertThat(borrowed.get(0)).containsEntry("id", "borrowed")
			.containsEntry("severity", "INFO")
			.containsEntry("title", "Money owed to friends")
			// bank 6000 + cash 800 = 6800 now; 1300 of it is owed
			.containsEntry("text", "You owe friends ₹1,300 in total: Ravi ₹800, Arun ₹500. "
					+ "After paying everyone back you would have ₹5,500 in bank and cash.");

		// last month is about last month: nothing about today
		mvc.perform(get("/api/analysis").param("period", "LAST_MONTH").with(TestData.as(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.insights[?(@.type == 'BORROWED')]").isEmpty());

		// all paid back: the insight is gone
		api.created(user, repay("Ravi", "800", "BANK"));
		api.created(user, repay("Arun", "500", "BANK"));
		mvc.perform(get("/api/analysis").param("period", "THIS_MONTH").with(TestData.as(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.spent").value("0.00"))
			.andExpect(jsonPath("$.insights[?(@.type == 'BORROWED')]").isEmpty());
	}

	// ---------------------------------------------------------------- invariant

	@Test
	void afterARandomSequenceBalancesAndDebtsStillAddUp() throws Exception {
		AuthUser user = onboardedUser();
		Random random = new Random(20261110);
		String[] friends = { "Ravi", "Arun", "Chitra" };
		String[] accounts = { "BANK", "CASH" };
		List<String> debtEntries = new ArrayList<>();

		for (int step = 1; step <= 150; step++) {
			int dice = random.nextInt(100);
			String friend = friends[random.nextInt(friends.length)];
			String amount = BigDecimal.valueOf(1 + random.nextInt(50_000), 2).toPlainString();
			if (dice < 35 || debtEntries.isEmpty()) {
				debtEntries.add(api.created(user, borrow(friend, amount, accounts[random.nextInt(2)])));
			}
			else if (dice < 70) {
				ResultActions result = api.create(user, repay(friend, amount, accounts[random.nextInt(2)]));
				int status = result.andReturn().getResponse().getStatus();
				// more than is owed (or nothing owed) is the only allowed refusal
				assertThat(status).isIn(201, 400);
				if (status == 201) {
					debtEntries.add(read(result, "$.entry.id"));
				}
			}
			else if (dice < 80) {
				api.created(user, expense("thing " + step, amount, "UPI"));
			}
			else {
				String id = debtEntries.get(random.nextInt(debtEntries.size()));
				int status = api.remove(user, id).andReturn().getResponse().getStatus();
				// a borrow that is already paid back is the only allowed refusal
				assertThat(status).isIn(200, 409);
				if (status == 200) {
					debtEntries.remove(id);
				}
			}
			if (step % 10 == 0) {
				BigDecimal owed = LedgerApi.assertNoFriendIsOwedLessThanNothing(jdbc, user);
				LedgerApi.assertLedgerExplainsBalances(jdbc, user);
				assertThat(new BigDecimal(LedgerApi.<String>read(api.balances(user), "$.owedToFriends")))
					.isEqualByComparingTo(owed);
				assertThat(new BigDecimal(LedgerApi.<String>read(api.debts(user), "$.total"))).isEqualByComparingTo(owed);
			}
		}
		// the list adds up to the total and shows only friends who are owed something
		ResultActions debts = api.debts(user).andExpect(status().isOk());
		List<String> owed = read(debts, "$.people[*].owed");
		BigDecimal sum = owed.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
		assertThat(sum).isEqualByComparingTo(LedgerApi.<String>read(debts, "$.total"));
		assertThat(owed).allMatch(value -> new BigDecimal(value).signum() > 0);
		assertThat(owed.stream().map(BigDecimal::new).toList()).isSortedAccordingTo((a, b) -> b.compareTo(a));
	}

}
