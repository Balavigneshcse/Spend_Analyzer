package com.spendanalyzer.ledger;

import static com.spendanalyzer.ledger.LedgerApi.adjustment;
import static com.spendanalyzer.ledger.LedgerApi.body;
import static com.spendanalyzer.ledger.LedgerApi.cardPayment;
import static com.spendanalyzer.ledger.LedgerApi.expense;
import static com.spendanalyzer.ledger.LedgerApi.income;
import static com.spendanalyzer.ledger.LedgerApi.read;
import static com.spendanalyzer.ledger.LedgerApi.withdrawal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.spendanalyzer.ai.api.Categorizer;
import com.spendanalyzer.ai.api.Prediction;
import com.spendanalyzer.model.PaymentMode;
import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Every entry type, edit and delete: the right balances move by the right amount. */
@IntegrationTest
class EntryFlowIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Clock clock;

	@Autowired
	ZoneId appZone;

	@Autowired
	Categorizer categorizer;

	LedgerApi api;

	@BeforeEach
	void setUp() {
		api = new LedgerApi(mvc);
	}

	private String today() {
		return LocalDate.now(clock.withZone(appZone)).toString();
	}

	/** A user who starts with bank 5000, cash 500 and a card due of 1000. */
	private AuthUser onboardedUser() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "5000", "500", "1000");
		return user;
	}

	// ---------------------------------------------------------------- create: each type

	@Test
	void expenseAnswersWithTheFullEntryAndTheNewBalances() throws Exception {
		AuthUser user = onboardedUser();
		// The AI is another module: compare with whatever it says for the same input.
		Prediction ai = categorizer.categorize(user.id(), "juse", new BigDecimal("40.00"), PaymentMode.UPI);

		ResultActions result = api.create(user, expense("juse", "40", "UPI"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.length()").value(16))
			.andExpect(jsonPath("$.entry.id").isString())
			.andExpect(jsonPath("$.entry.type").value("EXPENSE"))
			.andExpect(jsonPath("$.entry.amount").value("40.00"))
			.andExpect(jsonPath("$.entry.account").value("BANK"))
			.andExpect(jsonPath("$.entry.paymentMode").value("UPI"))
			.andExpect(jsonPath("$.entry.description").value("juse"))
			.andExpect(jsonPath("$.entry.category").value(ai.category().name()))
			.andExpect(jsonPath("$.entry.categoryLabel").value(ai.category().label()))
			.andExpect(jsonPath("$.entry.item").value(ai.item()))
			.andExpect(jsonPath("$.entry.aiSource").value(ai.source().name()))
			.andExpect(jsonPath("$.entry.needsReview").value(ai.needsReview()))
			.andExpect(jsonPath("$.entry.userCorrected").value(false))
			.andExpect(jsonPath("$.entry.date").value(today()))
			.andExpect(jsonPath("$.balances.bank").value("4960.00"))
			.andExpect(jsonPath("$.balances.cash").value("500.00"))
			.andExpect(jsonPath("$.balances.creditCardDue").value("1000.00"))
			.andExpect(jsonPath("$.balances.available").value("5460.00"))
			.andExpect(jsonPath("$.balances.onboarded").value(true));
		Number confidence = read(result, "$.entry.aiConfidence");
		assertThat(confidence.doubleValue()).isCloseTo(ai.confidence(), within(0.001));
		String createdAt = read(result, "$.entry.createdAt");
		assertThat(Instant.parse(createdAt)).isBetween(clock.instant().minusSeconds(60), clock.instant().plusSeconds(1));

		// a later read gives exactly the same entry
		String id = read(result, "$.entry.id");
		Map<String, Object> created = read(result, "$.entry");
		Map<String, Object> fetched = read(api.entry(user, id).andExpect(status().isOk()), "$.entry");
		assertThat(fetched).isEqualTo(created);
	}

	@Test
	void expenseMovesTheBalanceOfItsPaymentMode() throws Exception {
		AuthUser user = onboardedUser();
		api.create(user, expense("bus ticket", "25.50", "UPI"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.account").value("BANK"));
		api.assertBalances(user, "4974.50", "500.00", "1000.00");

		api.create(user, expense("pen", "10", "DEBIT_CARD"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.account").value("BANK"));
		api.assertBalances(user, "4964.50", "500.00", "1000.00");

		api.create(user, expense("briyany", "120", "CASH"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.account").value("CASH"));
		api.assertBalances(user, "4964.50", "380.00", "1000.00");

		// a card spend raises what is owed and leaves the money you have alone
		api.create(user, expense("shoes", "799.99", "CREDIT_CARD"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.account").value("CREDIT_CARD"));
		api.assertBalances(user, "4964.50", "380.00", "1799.99");
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);
	}

	@Test
	void incomeRaisesBankOrCash() throws Exception {
		AuthUser user = onboardedUser();
		Map<String, Object> salary = income("1500", "BANK");
		salary.put("description", "pocket money");
		api.create(user, salary)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.type").value("INCOME"))
			.andExpect(jsonPath("$.entry.amount").value("1500.00"))
			.andExpect(jsonPath("$.entry.account").value("BANK"))
			.andExpect(jsonPath("$.entry.description").value("pocket money"))
			.andExpect(jsonPath("$.entry.paymentMode").value(nullValue()))
			.andExpect(jsonPath("$.entry.category").value(nullValue()))
			.andExpect(jsonPath("$.entry.categoryLabel").value(nullValue()))
			.andExpect(jsonPath("$.entry.item").value(nullValue()))
			.andExpect(jsonPath("$.entry.aiConfidence").value(nullValue()))
			.andExpect(jsonPath("$.entry.aiSource").value(nullValue()))
			.andExpect(jsonPath("$.entry.needsReview").value(false))
			.andExpect(jsonPath("$.entry.length()").value(16))
			.andExpect(jsonPath("$.balances.bank").value("6500.00"));
		api.create(user, income("200", "CASH"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.description").value(nullValue()))
			.andExpect(jsonPath("$.balances.cash").value("700.00"));
		api.assertBalances(user, "6500.00", "700.00", "1000.00");
	}

	@Test
	void atmWithdrawalMovesBankToCash() throws Exception {
		AuthUser user = onboardedUser();
		api.create(user, withdrawal("2000"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.type").value("WITHDRAWAL"))
			.andExpect(jsonPath("$.entry.account").value("BANK"))
			.andExpect(jsonPath("$.balances.bank").value("3000.00"))
			.andExpect(jsonPath("$.balances.cash").value("2500.00"))
			// moving money around does not change how much there is
			.andExpect(jsonPath("$.balances.available").value("5500.00"));
		api.assertBalances(user, "3000.00", "2500.00", "1000.00");
	}

	@Test
	void creditCardSpendRaisesTheDueAndCardPaymentLowersBoth() throws Exception {
		AuthUser user = onboardedUser();
		api.created(user, expense("headphones", "1200", "CREDIT_CARD"));
		api.assertBalances(user, "5000.00", "500.00", "2200.00");

		api.create(user, cardPayment("1500", "BANK"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.type").value("CARD_PAYMENT"))
			.andExpect(jsonPath("$.entry.account").value("BANK"))
			.andExpect(jsonPath("$.balances.bank").value("3500.00"))
			.andExpect(jsonPath("$.balances.creditCardDue").value("700.00"));

		api.create(user, cardPayment("300", "CASH"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.account").value("CASH"))
			.andExpect(jsonPath("$.balances.cash").value("200.00"))
			.andExpect(jsonPath("$.balances.creditCardDue").value("400.00"));
		api.assertBalances(user, "3500.00", "200.00", "400.00");
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);
	}

	@Test
	void adjustmentSetsABalanceUpOrDownAndStoresTheDifference() throws Exception {
		AuthUser user = onboardedUser();
		api.create(user, adjustment("BANK", "5200"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.type").value("ADJUSTMENT"))
			.andExpect(jsonPath("$.entry.account").value("BANK"))
			.andExpect(jsonPath("$.entry.amount").value("200.00"))
			.andExpect(jsonPath("$.balances.bank").value("5200.00"));

		api.create(user, adjustment("CASH", "380"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.amount").value("-120.00"))
			.andExpect(jsonPath("$.balances.cash").value("380.00"));

		// for the card, newBalance is the amount due
		api.create(user, adjustment("CREDIT_CARD", "0"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.amount").value("-1000.00"))
			.andExpect(jsonPath("$.balances.creditCardDue").value("0.00"));
		api.assertBalances(user, "5200.00", "380.00", "0.00");

		// nothing to change
		api.create(user, adjustment("BANK", "5200.00"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation"))
			.andExpect(jsonPath("$.fields.newBalance").exists());
		api.assertBalances(user, "5200.00", "380.00", "0.00");
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);
	}

	@Test
	void balancesMayGoNegativeAndTheCardCanBeOverpaid() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.create(user, expense("laptop", "45000", "UPI"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.balances.bank").value("-45000.00"))
			.andExpect(jsonPath("$.balances.available").value("-45000.00"));
		api.create(user, cardPayment("250", "CASH"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.balances.cash").value("-250.00"))
			.andExpect(jsonPath("$.balances.creditCardDue").value("-250.00"));
		// correcting a negative balance back to a real one works too
		api.create(user, adjustment("BANK", "100"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.amount").value("45100.00"))
			.andExpect(jsonPath("$.balances.bank").value("100.00"));
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);
	}

	@Test
	void dateDefaultsToTodayAndAnEarlierDateIsKept() throws Exception {
		AuthUser user = onboardedUser();
		api.create(user, expense("tea", "10", "CASH")).andExpect(jsonPath("$.entry.date").value(today()));
		Map<String, Object> past = expense("tea", "10", "CASH");
		past.put("date", "2026-01-15");
		api.create(user, past).andExpect(status().isCreated()).andExpect(jsonPath("$.entry.date").value("2026-01-15"));
		Map<String, Object> tomorrow = expense("tea", "10", "CASH");
		tomorrow.put("date", LocalDate.parse(today()).plusDays(1).toString());
		api.create(user, tomorrow).andExpect(status().isCreated());
	}

	@Test
	void aBareJsonNumberWithPlainTextIsReadExactlyLikeTheString() throws Exception {
		AuthUser user = onboardedUser();
		String content = "{\"clientId\":\"" + UUID.randomUUID()
				+ "\",\"type\":\"EXPENSE\",\"description\":\"juse\",\"paymentMode\":\"UPI\",\"amount\":40.10}";
		mvc.perform(post("/api/entries").with(TestData.as(user)).contentType(MediaType.APPLICATION_JSON).content(content))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.amount").value("40.10"))
			.andExpect(jsonPath("$.balances.bank").value("4959.90"));
	}

	// ---------------------------------------------------------------- same clientId twice

	@Test
	void sendingTheSameClientIdAgainReturnsTheFirstEntryAndChargesOnce() throws Exception {
		AuthUser user = onboardedUser();
		Map<String, Object> request = expense("juse", "40", "UPI");
		String id = api.created(user, request);

		api.create(user, request)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.id").value(id))
			.andExpect(jsonPath("$.balances.bank").value("4960.00"));
		// even a different body with the same clientId is the same submit: the original wins
		Map<String, Object> other = expense("something else", "999", "CASH");
		other.put("clientId", request.get("clientId"));
		api.create(user, other)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.id").value(id))
			.andExpect(jsonPath("$.entry.description").value("juse"))
			.andExpect(jsonPath("$.entry.amount").value("40.00"));
		api.assertBalances(user, "4960.00", "500.00", "1000.00");
		api.list(user, "?type=EXPENSE").andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void replayOfAnAdjustmentIsAnsweredWith200NotNothingToChange() throws Exception {
		AuthUser user = onboardedUser();
		Map<String, Object> request = adjustment("BANK", "4000");
		String id = api.created(user, request);
		api.create(user, request)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.id").value(id))
			.andExpect(jsonPath("$.entry.amount").value("-1000.00"))
			.andExpect(jsonPath("$.balances.bank").value("4000.00"));
	}

	// ---------------------------------------------------------------- edit

	@Test
	void editingTheAmountMovesTheBalanceByTheDifference() throws Exception {
		AuthUser user = onboardedUser();
		String id = api.created(user, expense("juse", "40", "UPI"));
		api.edit(user, id, body("amount", "55.50"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.id").value(id))
			.andExpect(jsonPath("$.entry.amount").value("55.50"))
			.andExpect(jsonPath("$.entry.description").value("juse"))
			.andExpect(jsonPath("$.balances.bank").value("4944.50"));
		api.edit(user, id, body("amount", "5")).andExpect(jsonPath("$.balances.bank").value("4995.00"));
		api.assertBalances(user, "4995.00", "500.00", "1000.00");
	}

	@Test
	void editingUpiToCashMovesTheSpendFromBankToCash() throws Exception {
		AuthUser user = onboardedUser();
		String id = api.created(user, expense("juse", "40", "UPI"));
		api.assertBalances(user, "4960.00", "500.00", "1000.00");

		api.edit(user, id, body("paymentMode", "CASH"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.paymentMode").value("CASH"))
			.andExpect(jsonPath("$.entry.account").value("CASH"))
			.andExpect(jsonPath("$.balances.bank").value("5000.00"))
			.andExpect(jsonPath("$.balances.cash").value("460.00"));

		// to the credit card, with a new amount in the same request
		api.edit(user, id, body("paymentMode", "CREDIT_CARD", "amount", "70"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.account").value("CREDIT_CARD"))
			.andExpect(jsonPath("$.balances.cash").value("500.00"))
			.andExpect(jsonPath("$.balances.creditCardDue").value("1070.00"));

		// and back to the bank through the debit card
		api.edit(user, id, body("paymentMode", "DEBIT_CARD")).andExpect(status().isOk());
		api.assertBalances(user, "4930.00", "500.00", "1000.00");
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);
	}

	@Test
	void editingTheDateChangesOnlyTheDate() throws Exception {
		AuthUser user = onboardedUser();
		String id = api.created(user, expense("juse", "40", "UPI"));
		api.edit(user, id, body("date", "2026-03-09"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.date").value("2026-03-09"))
			.andExpect(jsonPath("$.entry.amount").value("40.00"))
			.andExpect(jsonPath("$.balances.bank").value("4960.00"));
		api.edit(user, id, body("date", "2999-01-01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.date").exists());
		api.entry(user, id).andExpect(jsonPath("$.entry.date").value("2026-03-09"));
	}

	@Test
	void editingTheCategoryRecordsTheUsersChoice() throws Exception {
		AuthUser user = onboardedUser();
		String id = api.created(user, expense("juse", "40", "UPI"));
		api.edit(user, id, body("category", "SNACKS_BEVERAGES"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.category").value("SNACKS_BEVERAGES"))
			.andExpect(jsonPath("$.entry.categoryLabel").value("Snacks & Beverages"))
			.andExpect(jsonPath("$.entry.userCorrected").value(true))
			.andExpect(jsonPath("$.entry.needsReview").value(false))
			.andExpect(jsonPath("$.balances.bank").value("4960.00"));
	}

	@Test
	void editingOnlyTheDescriptionAsksTheAiAgain() throws Exception {
		AuthUser user = onboardedUser();
		String id = api.created(user, expense("juse", "40", "UPI"));
		Prediction ai = categorizer.categorize(user.id(), "bus ticket", new BigDecimal("40.00"), PaymentMode.UPI);
		api.edit(user, id, body("description", "  bus ticket "))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.description").value("bus ticket"))
			.andExpect(jsonPath("$.entry.category").value(ai.category().name()))
			.andExpect(jsonPath("$.entry.item").value(ai.item()))
			.andExpect(jsonPath("$.entry.needsReview").value(ai.needsReview()))
			.andExpect(jsonPath("$.entry.userCorrected").value(false))
			.andExpect(jsonPath("$.balances.bank").value("4960.00"));
	}

	@Test
	void anEmptyEditChangesNothing() throws Exception {
		AuthUser user = onboardedUser();
		String id = api.created(user, expense("juse", "40", "UPI"));
		Map<String, Object> before = read(api.entry(user, id), "$.entry");
		Map<String, Object> after = read(api.edit(user, id, body()).andExpect(status().isOk()), "$.entry");
		assertThat(after).isEqualTo(before);
		api.assertBalances(user, "4960.00", "500.00", "1000.00");
	}

	@Test
	void onlyExpensesCanBeEdited() throws Exception {
		AuthUser user = onboardedUser();
		String incomeId = api.created(user, income("100", "BANK"));
		api.edit(user, incomeId, body("amount", "5"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation"));
		api.assertBalances(user, "5100.00", "500.00", "1000.00");
	}

	@Test
	void editAndDeleteOfUnknownIdsAre404() throws Exception {
		AuthUser user = onboardedUser();
		String unknown = UUID.randomUUID().toString();
		api.edit(user, unknown, body("amount", "5"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("not_found"));
		api.remove(user, unknown).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
		api.entry(user, unknown).andExpect(status().isNotFound());
		api.edit(user, "not-a-uuid", body("amount", "5")).andExpect(status().isNotFound());
		api.remove(user, "not-a-uuid").andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- delete

	@Test
	void deleteReversesEveryKindOfEntry() throws Exception {
		AuthUser user = onboardedUser();
		List<String> ids = new ArrayList<>();
		ids.add(api.created(user, expense("juse", "40", "UPI")));
		ids.add(api.created(user, expense("pen", "10", "DEBIT_CARD")));
		ids.add(api.created(user, expense("briyany", "120", "CASH")));
		ids.add(api.created(user, expense("shoes", "800", "CREDIT_CARD")));
		ids.add(api.created(user, income("1500", "BANK")));
		ids.add(api.created(user, income("50", "CASH")));
		ids.add(api.created(user, withdrawal("700")));
		ids.add(api.created(user, cardPayment("300", "BANK")));
		ids.add(api.created(user, cardPayment("100", "CASH")));
		ids.add(api.created(user, adjustment("CASH", "5")));
		ids.add(api.created(user, adjustment("CREDIT_CARD", "9000")));
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);

		for (String id : ids) {
			api.remove(user, id).andExpect(status().isOk()).andExpect(jsonPath("$.balances.bank").isString());
			LedgerApi.assertLedgerExplainsBalances(jdbc, user);
		}
		// everything undone: back to the opening balances
		api.assertBalances(user, "5000.00", "500.00", "1000.00");
		api.list(user, "").andExpect(jsonPath("$.total").value(3)); // the three opening entries remain
	}

	@Test
	void deleteAnswersWithBalancesAndASecondDeleteIs404() throws Exception {
		AuthUser user = onboardedUser();
		String id = api.created(user, expense("juse", "40", "UPI"));
		api.remove(user, id)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$.balances.bank").value("5000.00"))
			.andExpect(jsonPath("$.balances.onboarded").value(true));
		api.remove(user, id).andExpect(status().isNotFound());
		api.entry(user, id).andExpect(status().isNotFound());
		api.assertBalances(user, "5000.00", "500.00", "1000.00");
	}

	// ---------------------------------------------------------------- text is data, never markup

	@Test
	void anXssPayloadIsStoredRawAndReturnedAsAPlainJsonString() throws Exception {
		AuthUser user = onboardedUser();
		String payload = "<img src=x onerror=alert(1)>\"><script>alert('x')</script> & <b>";
		ResultActions result = api.create(user, expense(payload, "40", "UPI")).andExpect(status().isCreated());
		String id = read(result, "$.entry.id");
		assertThat(LedgerApi.<String>read(result, "$.entry.description")).isEqualTo(payload);

		String stored = jdbc.sql("SELECT description FROM entries WHERE id = ?")
			.param(UUID.fromString(id))
			.query(String.class)
			.single();
		assertThat(stored).isEqualTo(payload);

		ResultActions listed = api.list(user, "?type=EXPENSE").andExpect(status().isOk());
		assertThat(LedgerApi.<String>read(listed, "$.items[0].description")).isEqualTo(payload);
		assertThat(listed.andReturn().getResponse().getContentType()).startsWith("application/json");
		// not HTML-escaped anywhere on the way
		assertThat(LedgerApi.text(listed)).doesNotContain("&lt;").doesNotContain("&amp;");
	}

	// ---------------------------------------------------------------- invariant

	@Test
	void afterARandomSequenceEveryBalanceEqualsTheSumOfItsEntries() throws Exception {
		AuthUser user = onboardedUser();
		Random random = new Random(20261110);
		List<String> all = new ArrayList<>();
		List<String> expenses = new ArrayList<>();
		String[] modes = { "UPI", "DEBIT_CARD", "CREDIT_CARD", "CASH" };
		String[] accounts = { "BANK", "CASH", "CREDIT_CARD" };

		for (int step = 1; step <= 150; step++) {
			int dice = random.nextInt(100);
			if (dice < 55 || all.isEmpty()) {
				String amount = amount(random);
				Map<String, Object> request = switch (random.nextInt(6)) {
					case 0, 1 -> expense("thing " + step, amount, modes[random.nextInt(4)]);
					case 2 -> income(amount, accounts[random.nextInt(2)]);
					case 3 -> withdrawal(amount);
					case 4 -> cardPayment(amount, accounts[random.nextInt(2)]);
					default -> adjustment(accounts[random.nextInt(3)], amount);
				};
				ResultActions result = api.create(user, request);
				int status = result.andReturn().getResponse().getStatus();
				// an adjustment to the value the balance already has is the only allowed refusal
				assertThat(status).isIn(201, 400);
				if (status == 201) {
					String id = read(result, "$.entry.id");
					all.add(id);
					if ("EXPENSE".equals(request.get("type"))) {
						expenses.add(id);
					}
				}
			}
			else if (dice < 80 && !expenses.isEmpty()) {
				String id = expenses.get(random.nextInt(expenses.size()));
				Map<String, Object> change = body();
				if (random.nextBoolean()) {
					change.put("amount", amount(random));
				}
				if (random.nextBoolean()) {
					change.put("paymentMode", modes[random.nextInt(4)]);
				}
				api.edit(user, id, change).andExpect(status().isOk());
			}
			else {
				String id = all.remove(random.nextInt(all.size()));
				expenses.remove(id);
				api.remove(user, id).andExpect(status().isOk());
			}
			if (step % 10 == 0) {
				LedgerApi.assertLedgerExplainsBalances(jdbc, user);
			}
		}
		LedgerApi.assertLedgerExplainsBalances(jdbc, user);

		// and the API shows exactly what is stored
		Map<String, Object> shown = read(api.balances(user), "$");
		assertThat(new BigDecimal((String) shown.get("bank"))).isEqualByComparingTo(LedgerApi.balanceInDb(jdbc, user, "BANK"));
		assertThat(new BigDecimal((String) shown.get("cash"))).isEqualByComparingTo(LedgerApi.balanceInDb(jdbc, user, "CASH"));
		assertThat(new BigDecimal((String) shown.get("creditCardDue")))
			.isEqualByComparingTo(LedgerApi.balanceInDb(jdbc, user, "CREDIT_CARD"));
		assertThat(new BigDecimal((String) shown.get("available")))
			.isEqualByComparingTo(LedgerApi.balanceInDb(jdbc, user, "BANK").add(LedgerApi.balanceInDb(jdbc, user, "CASH")));
	}

	/** 0.01 to 3000.00 with paise, as text. */
	private static String amount(Random random) {
		return BigDecimal.valueOf(1 + random.nextInt(300_000), 2).toPlainString();
	}

}
