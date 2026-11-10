package com.spendanalyzer.ledger;

import static com.spendanalyzer.ledger.LedgerApi.body;
import static com.spendanalyzer.ledger.LedgerApi.expense;
import static com.spendanalyzer.ledger.LedgerApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.spendanalyzer.ai.api.Categorizer;
import com.spendanalyzer.ai.api.Prediction;
import com.spendanalyzer.ai.api.PredictionSource;
import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.EntryType;
import com.spendanalyzer.model.PaymentMode;
import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How the ledger talks to the AI: when it asks, what it stores and when it teaches.
 * <p>
 * The first half goes through HTTP with whatever {@link Categorizer} the application has and only
 * checks stored columns. The second half builds a {@link LedgerService} by hand around a recording
 * categorizer (same repositories, same database, no second application context) to see the exact
 * {@code learn} calls.
 */
@IntegrationTest
class CategoryFlowIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Categorizer categorizer;

	@Autowired
	EntryRepository entries;

	@Autowired
	AccountRepository accounts;

	@Autowired
	BalanceReader balanceReader;

	@Autowired
	Clock clock;

	@Autowired
	PlatformTransactionManager transactionManager;

	LedgerApi api;

	RecordingCategorizer recording;

	LedgerService ledger;

	TransactionTemplate tx;

	@BeforeEach
	void setUp() {
		api = new LedgerApi(mvc);
		recording = new RecordingCategorizer();
		ledger = new LedgerService(entries, accounts, new SafeCategorizer(recording), balanceReader, clock);
		tx = new TransactionTemplate(transactionManager);
	}

	// ---------------------------------------------------------------- through HTTP: stored columns

	@Test
	void withoutACategoryTheAiDecidesAndEverythingItSaidIsStored() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		Prediction ai = categorizer.categorize(user.id(), "juse", new BigDecimal("40.00"), PaymentMode.UPI);
		String id = api.created(user, expense("juse", "40", "UPI"));

		Map<String, Object> row = row(id);
		assertThat(row).containsEntry("category", ai.category().name())
			.containsEntry("ai_category", ai.category().name())
			.containsEntry("item", ai.item())
			.containsEntry("ai_source", ai.source().name())
			.containsEntry("needs_review", ai.needsReview())
			.containsEntry("user_corrected", false);
		assertThat(((Number) row.get("ai_confidence")).doubleValue()).isCloseTo(ai.confidence(), within(0.001));
	}

	@Test
	void anExplicitCategoryWinsAndIsMarkedAsTheUsersChoice() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		// asked before the post: afterwards the AI may already have learned from it
		Prediction ai = categorizer.categorize(user.id(), "juse", new BigDecimal("40.00"), PaymentMode.UPI);
		Map<String, Object> request = expense("juse", "40", "UPI");
		request.put("category", "SHOPPING");

		String id = read(api.create(user, request)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.category").value("SHOPPING"))
			.andExpect(jsonPath("$.entry.categoryLabel").value("Shopping"))
			.andExpect(jsonPath("$.entry.userCorrected").value(true))
			.andExpect(jsonPath("$.entry.needsReview").value(false))
			.andExpect(jsonPath("$.entry.item").value(ai.item())), "$.entry.id");

		// the AI's own opinion is still recorded next to the user's choice
		assertThat(row(id)).containsEntry("category", "SHOPPING")
			.containsEntry("ai_category", ai.category().name())
			.containsEntry("ai_source", ai.source().name())
			.containsEntry("user_corrected", true)
			.containsEntry("needs_review", false);
	}

	@Test
	void patchingTheCategoryMarksTheUsersChoiceAndKeepsTheAiColumns() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		String id = api.created(user, expense("briyany", "150", "CASH"));
		Map<String, Object> before = row(id);

		api.edit(user, id, body("category", "FOOD_MEALS"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.category").value("FOOD_MEALS"))
			.andExpect(jsonPath("$.entry.userCorrected").value(true))
			.andExpect(jsonPath("$.entry.needsReview").value(false));

		assertThat(row(id)).containsEntry("category", "FOOD_MEALS")
			.containsEntry("user_corrected", true)
			.containsEntry("needs_review", false)
			.containsEntry("ai_category", before.get("ai_category"))
			.containsEntry("item", before.get("item"));
	}

	@Test
	void afterAUserChoiceANewDescriptionKeepsTheCategoryButRefreshesTheItem() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		Map<String, Object> request = expense("juse", "40", "UPI");
		request.put("category", "SNACKS_BEVERAGES");
		String id = api.created(user, request);

		Prediction ai = categorizer.categorize(user.id(), "mango juse large", new BigDecimal("40.00"), PaymentMode.UPI);
		api.edit(user, id, body("description", "mango juse large"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.description").value("mango juse large"))
			.andExpect(jsonPath("$.entry.category").value("SNACKS_BEVERAGES"))
			.andExpect(jsonPath("$.entry.userCorrected").value(true))
			.andExpect(jsonPath("$.entry.needsReview").value(false))
			.andExpect(jsonPath("$.entry.item").value(ai.item()));
	}

	// ---------------------------------------------------------------- recording categorizer: learn calls

	@Test
	void theAiIsNeverTaughtItsOwnGuess() {
		AuthUser user = TestData.createUser(jdbc);
		recording.answer = prediction(Category.SNACKS_BEVERAGES, 0.88, "juice", false);

		EntryView entry = create(user, newExpense("juse", "40.00", PaymentMode.UPI, null)).entry();

		assertThat(entry.category()).isEqualTo("SNACKS_BEVERAGES");
		assertThat(entry.item()).isEqualTo("juice");
		assertThat(entry.aiConfidence()).isEqualTo(0.88);
		assertThat(entry.aiSource()).isEqualTo("LEXICON_FUZZY");
		assertThat(entry.needsReview()).isFalse();
		assertThat(entry.userCorrected()).isFalse();
		assertThat(recording.asked).containsExactly("juse|40.00|UPI|" + user.id());
		assertThat(recording.learned).isEmpty();
	}

	@Test
	void anUnsureGuessIsStoredAsNeedsReview() {
		AuthUser user = TestData.createUser(jdbc);
		recording.answer = new Prediction(Category.OTHER, 0.2, "xyzzy", "Not recognised", true, List.of(),
				PredictionSource.ABSTAIN);
		EntryView entry = create(user, newExpense("xyzzy", "40.00", PaymentMode.CASH, null)).entry();
		assertThat(entry.category()).isEqualTo("OTHER");
		assertThat(entry.needsReview()).isTrue();
		assertThat(recording.learned).isEmpty();
	}

	@Test
	void choosingADifferentCategoryTeachesACorrection() {
		AuthUser user = TestData.createUser(jdbc);
		recording.answer = prediction(Category.SNACKS_BEVERAGES, 0.88, "juice", true);

		EntryView entry = create(user, newExpense("juse", "40.00", PaymentMode.UPI, Category.HEALTH_MEDICINE)).entry();

		assertThat(entry.category()).isEqualTo("HEALTH_MEDICINE");
		assertThat(entry.userCorrected()).isTrue();
		assertThat(entry.needsReview()).isFalse();
		assertThat(entry.item()).isEqualTo("juice");
		assertThat(recording.learned).containsExactly(new Lesson(user.id(), "juse", Category.HEALTH_MEDICINE, true));
		assertThat(row(entry.id())).containsEntry("ai_category", "SNACKS_BEVERAGES");
	}

	@Test
	void choosingTheCategoryTheAiAlsoChoseTeachesAConfirmation() {
		AuthUser user = TestData.createUser(jdbc);
		recording.answer = prediction(Category.SNACKS_BEVERAGES, 0.88, "juice", false);

		EntryView entry = create(user, newExpense("juse", "40.00", PaymentMode.UPI, Category.SNACKS_BEVERAGES)).entry();

		assertThat(entry.userCorrected()).isTrue();
		assertThat(recording.learned).containsExactly(new Lesson(user.id(), "juse", Category.SNACKS_BEVERAGES, false));
	}

	@Test
	void aReplayOfTheSameClientIdTeachesNothingMore() {
		AuthUser user = TestData.createUser(jdbc);
		NewEntry request = newExpense("juse", "40.00", PaymentMode.UPI, Category.SHOPPING);
		assertThat(create(user, request).created()).isTrue();
		assertThat(create(user, request).created()).isFalse();
		assertThat(recording.learned).hasSize(1);
		assertThat(recording.asked).hasSize(1);
	}

	@Test
	void patchWithACategoryTeachesAndComparesWithWhatTheAiSaid() {
		AuthUser user = TestData.createUser(jdbc);
		recording.answer = prediction(Category.SNACKS_BEVERAGES, 0.88, "juice", true);
		UUID id = UUID.fromString(create(user, newExpense("juse", "40.00", PaymentMode.UPI, null)).entry().id());
		recording.asked.clear();

		EntryView corrected = edit(user, id, new EntryChange(null, null, null, null, Category.GROCERIES)).entry();
		assertThat(corrected.category()).isEqualTo("GROCERIES");
		assertThat(corrected.userCorrected()).isTrue();
		assertThat(corrected.needsReview()).isFalse();
		// the text did not change, so the AI is not asked again
		assertThat(recording.asked).isEmpty();
		assertThat(recording.learned).containsExactly(new Lesson(user.id(), "juse", Category.GROCERIES, true));

		// confirming the AI's own category is a lesson too, but not a correction
		edit(user, id, new EntryChange(null, null, null, null, Category.SNACKS_BEVERAGES));
		assertThat(recording.learned).last().isEqualTo(new Lesson(user.id(), "juse", Category.SNACKS_BEVERAGES, false));
	}

	@Test
	void patchWithDescriptionAndCategoryTeachesTheNewText() {
		AuthUser user = TestData.createUser(jdbc);
		recording.answer = prediction(Category.OTHER, 0.1, "jx", true);
		UUID id = UUID.fromString(create(user, newExpense("jx", "40.00", PaymentMode.UPI, null)).entry().id());
		recording.answer = prediction(Category.SNACKS_BEVERAGES, 0.9, "juice", false);

		EntryView entry = edit(user, id, new EntryChange("juice", null, null, null, Category.SNACKS_BEVERAGES)).entry();

		assertThat(entry.item()).isEqualTo("juice");
		assertThat(entry.category()).isEqualTo("SNACKS_BEVERAGES");
		assertThat(recording.learned).containsExactly(new Lesson(user.id(), "juice", Category.SNACKS_BEVERAGES, false));
		assertThat(row(entry.id())).containsEntry("ai_category", "SNACKS_BEVERAGES");
	}

	@Test
	void patchOfOnlyTheDescriptionRerunsTheAiAndTeachesNothing() {
		AuthUser user = TestData.createUser(jdbc);
		recording.answer = prediction(Category.OTHER, 0.1, "jx", true);
		UUID id = UUID.fromString(create(user, newExpense("jx", "40.00", PaymentMode.UPI, null)).entry().id());

		recording.answer = prediction(Category.TRANSPORT_FUEL, 0.95, "bus ticket", false);
		EntryView entry = edit(user, id, new EntryChange("bus ticket", null, null, null, null)).entry();

		assertThat(entry.category()).isEqualTo("TRANSPORT_FUEL");
		assertThat(entry.item()).isEqualTo("bus ticket");
		assertThat(entry.needsReview()).isFalse();
		assertThat(entry.userCorrected()).isFalse();
		assertThat(recording.asked).last().isEqualTo("bus ticket|40.00|UPI|" + user.id());
		assertThat(recording.learned).isEmpty();
	}

	@Test
	void patchOfOnlyTheDescriptionKeepsAUserChosenCategoryButRefreshesTheItem() {
		AuthUser user = TestData.createUser(jdbc);
		recording.answer = prediction(Category.SNACKS_BEVERAGES, 0.88, "juice", false);
		UUID id = UUID
			.fromString(create(user, newExpense("juse", "40.00", PaymentMode.UPI, Category.HEALTH_MEDICINE)).entry().id());
		recording.learned.clear();

		recording.answer = prediction(Category.FOOD_MEALS, 0.9, "glucose drink", true);
		EntryView entry = edit(user, id, new EntryChange("glucose drink", null, null, null, null)).entry();

		assertThat(entry.category()).isEqualTo("HEALTH_MEDICINE");
		assertThat(entry.userCorrected()).isTrue();
		assertThat(entry.needsReview()).isFalse();
		assertThat(entry.item()).isEqualTo("glucose drink");
		assertThat(recording.learned).isEmpty();
		assertThat(row(entry.id())).containsEntry("ai_category", "FOOD_MEALS");
	}

	@Test
	void patchOfAmountModeOrDateDoesNotInvolveTheAi() {
		AuthUser user = TestData.createUser(jdbc);
		UUID id = UUID.fromString(create(user, newExpense("juse", "40.00", PaymentMode.UPI, null)).entry().id());
		recording.asked.clear();

		edit(user, id, new EntryChange(null, new BigDecimal("55.00"), PaymentMode.CASH, LocalDate.of(2026, 1, 2), null));
		// the same text sent again is not a change either
		edit(user, id, new EntryChange("juse", null, null, null, null));

		assertThat(recording.asked).isEmpty();
		assertThat(recording.learned).isEmpty();
	}

	@Test
	void entriesThatAreNotSpendsNeverReachTheAi() {
		AuthUser user = TestData.createUser(jdbc);
		create(user, new NewEntry(UUID.randomUUID(), EntryType.INCOME, LocalDate.of(2026, 1, 2), new BigDecimal("500.00"),
				AccountType.BANK, null, "pocket money", null, null));
		create(user, new NewEntry(UUID.randomUUID(), EntryType.ADJUSTMENT, LocalDate.of(2026, 1, 2), null,
				AccountType.CASH, null, null, null, new BigDecimal("75.00")));
		assertThat(recording.asked).isEmpty();
		assertThat(recording.learned).isEmpty();
	}

	@Test
	void previewAsksButNeverTeachesOrSaves() {
		AuthUser user = TestData.createUser(jdbc);
		recording.answer = new Prediction(Category.SNACKS_BEVERAGES, 0.8768, "juice", "\"juse\" sounds like \"juice\"",
				false, List.of(new Prediction.Alternative(Category.FOOD_MEALS, 0.07)), PredictionSource.LEXICON_FUZZY);
		EntryQueries queries = new EntryQueries(entries, new SafeCategorizer(recording));

		PreviewView preview = queries.preview(user.id(), "juse", "40", "UPI");

		assertThat(preview).isEqualTo(new PreviewView("SNACKS_BEVERAGES", "Snacks & Beverages", 0.877, "juice",
				"\"juse\" sounds like \"juice\"", false, "LEXICON_FUZZY",
				List.of(new PreviewView.Alternative("FOOD_MEALS", "Food & Meals", 0.07))));
		assertThat(recording.asked).containsExactly("juse|40.00|UPI|" + user.id());
		// half-typed hints are dropped instead of failing the preview
		queries.preview(user.id(), "juse", "4.", "paytm");
		assertThat(recording.asked).last().isEqualTo("juse|null|null|" + user.id());
		assertThat(recording.learned).isEmpty();
		assertThat(entries.count(user.id(), EntryFilter.none())).isZero();
	}

	@Test
	void aSloppyAnswerOfTheAiCannotBreakSaving() {
		AuthUser user = TestData.createUser(jdbc);
		// too long item, confidence out of range, missing parts
		recording.answer = new Prediction(Category.SHOPPING, 7.5, "x".repeat(200), null, false, null, null);
		EntryView entry = create(user, newExpense("something", "40.00", PaymentMode.UPI, null)).entry();
		assertThat(entry.item()).hasSize(60);
		assertThat(entry.aiConfidence()).isEqualTo(1.0);
		assertThat(entry.aiSource()).isEqualTo("ABSTAIN");

		recording.answer = null;
		EntryView fallback = create(user, newExpense("Mystery Thing", "40.00", PaymentMode.UPI, null)).entry();
		assertThat(fallback.category()).isEqualTo("OTHER");
		assertThat(fallback.needsReview()).isTrue();
		assertThat(fallback.item()).isEqualTo("mystery thing");
	}

	// ---------------------------------------------------------------- helpers

	private LedgerService.Saved create(AuthUser user, NewEntry request) {
		return tx.execute(status -> ledger.create(user.id(), request));
	}

	private LedgerService.Saved edit(AuthUser user, UUID id, EntryChange change) {
		return tx.execute(status -> ledger.edit(user.id(), id, change));
	}

	private static NewEntry newExpense(String description, String amount, PaymentMode mode, Category category) {
		return new NewEntry(UUID.randomUUID(), EntryType.EXPENSE, LocalDate.of(2026, 1, 2), new BigDecimal(amount),
				mode.account(), mode, description, category, null);
	}

	private static Prediction prediction(Category category, double confidence, String item, boolean needsReview) {
		return new Prediction(category, confidence, item, "because", needsReview, List.of(), PredictionSource.LEXICON_FUZZY);
	}

	private Map<String, Object> row(String id) {
		return jdbc.sql("SELECT * FROM entries WHERE id = ?").param(UUID.fromString(id)).query().singleRow();
	}

	record Lesson(UUID userId, String text, Category category, boolean wasCorrection) {
	}

	/** Answers with a fixed prediction and writes down every call. */
	static class RecordingCategorizer implements Categorizer {

		Prediction answer = new Prediction(Category.OTHER, 0.0, "thing", "Not recognised", true, List.of(),
				PredictionSource.ABSTAIN);

		final List<String> asked = new ArrayList<>();

		final List<Lesson> learned = new ArrayList<>();

		@Override
		public Prediction categorize(UUID userId, String text, BigDecimal amount, PaymentMode mode) {
			asked.add(text + "|" + amount + "|" + mode + "|" + userId);
			return answer;
		}

		@Override
		public void learn(UUID userId, String text, Category finalCategory, boolean wasCorrection) {
			learned.add(new Lesson(userId, text, finalCategory, wasCorrection));
		}

	}

}
