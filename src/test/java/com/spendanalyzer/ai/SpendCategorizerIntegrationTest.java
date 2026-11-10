package com.spendanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.spendanalyzer.ai.api.Categorizer;
import com.spendanalyzer.ai.api.Prediction;
import com.spendanalyzer.ai.api.PredictionSource;
import com.spendanalyzer.ai.learn.JdbcUserMemoryStore;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The real Spring bean and the real tables: learn() then categorize() for two users. */
@IntegrationTest
class SpendCategorizerIntegrationTest {

	@Autowired
	Categorizer categorizer;

	@Autowired
	SpendCategorizer bean;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Clock clock;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void thereIsExactlyOneCategorizerAndItIsTheRealOne() {
		assertThat(categorizer).isSameAs(bean);
		assertThat(bean.stats().items()).isGreaterThanOrEqualTo(900);
	}

	@Test
	void theModelIsTrainedAndAnswersTheUsersExamples() {
		// under the "test" profile the bean trains while it is constructed
		assertThat(bean.isModelReady()).isTrue();
		assertThat(bean.awaitModel(Duration.ofSeconds(60))).isTrue();
		assertThat(bean.stats().trainingExamples()).isGreaterThan(10_000);

		Prediction juse = categorizer.categorize(null, "juse", new BigDecimal("40.00"), PaymentMode.UPI);
		assertThat(juse.category()).isEqualTo(Category.SNACKS_BEVERAGES);
		assertThat(juse.item()).isEqualTo("juice");
		assertThat(juse.confidence()).isGreaterThanOrEqualTo(0.80);
		assertThat(juse.needsReview()).isFalse();
		assertThat(categorizer.categorize(null, "bus ticket", null, PaymentMode.CASH).category())
			.isEqualTo(Category.TRANSPORT_FUEL);
		assertThat(categorizer.categorize(null, null, null, null).category()).isEqualTo(Category.OTHER);
	}

	@Test
	void learnsPerUserThroughTheDatabase() {
		UUID asha = TestData.createUser(jdbc).id();
		UUID bala = TestData.createUser(jdbc).id();
		Prediction before = categorizer.categorize(bala, "juse", null, PaymentMode.UPI);

		categorizer.learn(asha, "Juse", Category.HEALTH_MEDICINE, true);

		// takes effect at once for the same text ...
		Prediction same = categorizer.categorize(asha, "juse", null, PaymentMode.UPI);
		assertThat(same.category()).isEqualTo(Category.HEALTH_MEDICINE);
		assertThat(same.source()).isEqualTo(PredictionSource.USER_MEMORY);
		assertThat(same.confidence()).isGreaterThanOrEqualTo(0.95);
		assertThat(same.needsReview()).isFalse();
		// ... for the same text with a quantity or a filler ...
		assertThat(categorizer.categorize(asha, "juse 2", null, null).category()).isEqualTo(Category.HEALTH_MEDICINE);
		assertThat(categorizer.categorize(asha, "juse shop", null, null).category())
			.isEqualTo(Category.HEALTH_MEDICINE);
		// ... and, through the word weights, for a text that merely contains the word
		Prediction variant = categorizer.categorize(asha, "juse tetra", null, null);
		assertThat(variant.category()).isEqualTo(Category.HEALTH_MEDICINE);
		assertThat(variant.source()).isEqualTo(PredictionSource.ENSEMBLE);

		// the other user, and "no user", see none of it
		assertThat(categorizer.categorize(bala, "juse", null, PaymentMode.UPI)).isEqualTo(before);
		assertThat(categorizer.categorize(bala, "juse tetra", null, null).category())
			.isNotEqualTo(Category.HEALTH_MEDICINE);
		assertThat(categorizer.categorize(null, "juse", null, null).category()).isEqualTo(Category.SNACKS_BEVERAGES);

		// what was stored, and for whom
		assertThat(memoryRows(asha)).containsExactly(Map.of("norm_text", "juse", "category", "HEALTH_MEDICINE", "hits", 1));
		assertThat(memoryRows(bala)).isEmpty();
		assertThat(count("ai_user_token_stats", asha)).isEqualTo(1);
		assertThat(count("ai_user_token_stats", bala)).isZero();
	}

	@Test
	void confirmationsCountHitsAndCorrectionsReplace() {
		UUID user = TestData.createUser(jdbc).id();

		categorizer.learn(user, "fluxomat", Category.SNACKS_BEVERAGES, true);
		categorizer.learn(user, "fluxomat 2", Category.SNACKS_BEVERAGES, false);
		assertThat(memoryRows(user)).containsExactly(Map.of("norm_text", "fluxomat", "category", "SNACKS_BEVERAGES", "hits", 2));
		assertThat(weight(user, "fluxomat", Category.SNACKS_BEVERAGES)).isEqualTo(1.25f);

		categorizer.learn(user, "fluxomat", Category.GROCERIES, true);
		assertThat(memoryRows(user)).containsExactly(Map.of("norm_text", "fluxomat", "category", "GROCERIES", "hits", 1));
		assertThat(weight(user, "fluxomat", Category.SNACKS_BEVERAGES)).isEqualTo(0.625f);
		assertThat(weight(user, "fluxomat", Category.GROCERIES)).isEqualTo(1.0f);
		assertThat(categorizer.categorize(user, "fluxomat", null, null).category()).isEqualTo(Category.GROCERIES);

		// timestamps come from the application clock
		Integer recent = jdbc.sql("SELECT count(*) FROM ai_user_memory WHERE user_id = ? AND updated_at > ?")
			.params(user, java.sql.Timestamp.from(clock.instant().minusSeconds(300)))
			.query(Integer.class)
			.single();
		assertThat(recent).isEqualTo(1);
	}

	@Test
	void learningJoinsTheCallersTransaction() {
		UUID user = TestData.createUser(jdbc).id();

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			categorizer.learn(user, "blorfen", Category.SHOPPING, true);
			// visible inside the transaction ...
			assertThat(categorizer.categorize(user, "blorfen", null, null).category()).isEqualTo(Category.SHOPPING);
			status.setRollbackOnly();
		});

		// ... and gone with it
		assertThat(categorizer.categorize(user, "blorfen", null, null).category()).isEqualTo(Category.OTHER);
		assertThat(memoryRows(user)).isEmpty();
		assertThat(count("ai_user_token_stats", user)).isZero();
	}

	@Test
	void oddTextsAreStoredSafely() {
		UUID user = TestData.createUser(jdbc).id();
		String sql = "x'; DROP TABLE ai_user_memory; --";
		String tamil = "ஜூஸ் கடை";
		String huge = "wxyz ".repeat(2000);

		categorizer.learn(user, sql, Category.SHOPPING, true);
		categorizer.learn(user, tamil, Category.SNACKS_BEVERAGES, true);
		categorizer.learn(user, huge, Category.GROCERIES, true);
		categorizer.learn(user, "\u0000\u0001", Category.GROCERIES, true);
		categorizer.learn(user, "🍕🍕", Category.FOOD_MEALS, true);

		assertThat(categorizer.categorize(user, sql, null, null).category()).isEqualTo(Category.SHOPPING);
		assertThat(categorizer.categorize(user, tamil, null, null).category()).isEqualTo(Category.SNACKS_BEVERAGES);
		assertThat(categorizer.categorize(user, tamil, null, null).source()).isEqualTo(PredictionSource.USER_MEMORY);
		assertThat(categorizer.categorize(user, huge, null, null).category()).isEqualTo(Category.GROCERIES);
		assertThat(count("ai_user_memory", user)).isEqualTo(3);
		Integer longest = jdbc.sql("SELECT max(length(norm_text)) FROM ai_user_memory WHERE user_id = ?")
			.param(user)
			.query(Integer.class)
			.single();
		assertThat(longest).isLessThanOrEqualTo(120);
	}

	@Test
	void rowsPerUserAreCapped() {
		UUID user = TestData.createUser(jdbc).id();
		UUID other = TestData.createUser(jdbc).id();
		JdbcUserMemoryStore small = new JdbcUserMemoryStore(jdbc, clock, 3, 4);
		small.remember(other, "keep me", Category.SHOPPING);
		small.reinforce(other, List.of("keep"), Category.SHOPPING, 1.0, 3.0, true);

		for (int i = 0; i < 10; i++) {
			small.remember(user, "text " + i, Category.SHOPPING);
			small.reinforce(user, List.of("word" + i, "shared"), Category.SHOPPING, 0.5, 3.0, false);
		}

		assertThat(count("ai_user_memory", user)).isEqualTo(3);
		assertThat(count("ai_user_token_stats", user)).isEqualTo(4);
		// the newest survive, and the weight cap holds
		assertThat(small.recall(user, "text 9")).contains(Category.SHOPPING);
		assertThat(small.recall(user, "text 0")).isEmpty();
		assertThat(small.wordWeights(user, List.of("shared")).get("shared").get(Category.SHOPPING)).isEqualTo(3.0);
		// trimming one user never touches another
		assertThat(small.recall(other, "keep me")).contains(Category.SHOPPING);
		assertThat(small.wordWeights(other, List.of("keep", "absent"))).containsOnlyKeys("keep");
	}

	private List<Map<String, Object>> memoryRows(UUID user) {
		return jdbc.sql("SELECT norm_text, category, hits FROM ai_user_memory WHERE user_id = ? ORDER BY norm_text")
			.param(user)
			.query()
			.listOfRows();
	}

	private int count(String table, UUID user) {
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = ?").param(user).query(Integer.class).single();
	}

	private float weight(UUID user, String token, Category category) {
		return jdbc.sql("SELECT weight FROM ai_user_token_stats WHERE user_id = ? AND token = ? AND category = ?")
			.params(user, token, category.name())
			.query(Float.class)
			.single();
	}

}
