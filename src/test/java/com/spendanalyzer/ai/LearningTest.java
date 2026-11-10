package com.spendanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.spendanalyzer.ai.api.Prediction;
import com.spendanalyzer.ai.api.PredictionSource;
import com.spendanalyzer.ai.engine.CategorizerEngine;
import com.spendanalyzer.ai.learn.InMemoryUserMemoryStore;
import com.spendanalyzer.model.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Per-user learning on the plain engine with the in-memory store. */
class LearningTest {

	private InMemoryUserMemoryStore store;

	private CategorizerEngine engine;

	private final UUID asha = UUID.randomUUID();

	private final UUID bala = UUID.randomUUID();

	@BeforeEach
	void freshMemory() {
		store = new InMemoryUserMemoryStore();
		engine = TestEngines.withMemory(store);
	}

	@Test
	void aCorrectionTakesEffectImmediately() {
		assertThat(engine.categorize(asha, "juse", null).category()).isEqualTo(Category.SNACKS_BEVERAGES);

		engine.learn(asha, "juse", Category.HEALTH_MEDICINE, true);

		Prediction after = engine.categorize(asha, "juse", null);
		assertThat(after.category()).isEqualTo(Category.HEALTH_MEDICINE);
		assertThat(after.source()).isEqualTo(PredictionSource.USER_MEMORY);
		assertThat(after.confidence()).isGreaterThanOrEqualTo(0.95);
		assertThat(after.needsReview()).isFalse();
		assertThat(after.explanation()).isEqualTo("You chose Health & Medicine for \"juse\" before.");
		// the general word list no longer names the item: for this user "juse" is not juice
		assertThat(after.item()).isEqualTo("juse");
	}

	@Test
	void theSameTextInAnotherShapeIsTheSameMemory() {
		engine.learn(asha, "Juse", Category.HEALTH_MEDICINE, true);
		for (String variant : List.of("juse", "JUSE!!", "  juse  ", "juse 2", "2 juse", "juse 40rs", "paid for juse",
				"juse shop")) {
			Prediction prediction = engine.categorize(asha, variant, null);
			assertThat(prediction.category()).as(variant).isEqualTo(Category.HEALTH_MEDICINE);
			assertThat(prediction.source()).as(variant).isEqualTo(PredictionSource.USER_MEMORY);
		}
	}

	@Test
	void aCorrectionGeneralisesToTextsThatContainTheWord() {
		engine.learn(asha, "juse", Category.HEALTH_MEDICINE, true);

		// not the remembered text itself: the single word carries the lesson
		for (String variant : List.of("juse tetra", "juse zzkq 2", "orange juse")) {
			Prediction prediction = engine.categorize(asha, variant, null);
			assertThat(prediction.category()).as(variant).isEqualTo(Category.HEALTH_MEDICINE);
			assertThat(prediction.source()).as(variant).isEqualTo(PredictionSource.ENSEMBLE);
			assertThat(prediction.confidence()).as(variant).isGreaterThanOrEqualTo(CategorizerEngine.REVIEW);
			assertThat(prediction.explanation()).as(variant).contains("You filed \"juse\" under Health & Medicine");
		}
	}

	@Test
	void anUnknownWordCanBeTaught() {
		assertThat(engine.categorize(asha, "fluxomat", null).category()).isEqualTo(Category.OTHER);

		engine.learn(asha, "fluxomat", Category.SNACKS_BEVERAGES, true);

		Prediction same = engine.categorize(asha, "fluxomat", null);
		assertThat(same.category()).isEqualTo(Category.SNACKS_BEVERAGES);
		assertThat(same.source()).isEqualTo(PredictionSource.USER_MEMORY);
		assertThat(same.item()).isEqualTo("fluxomat");

		Prediction variant = engine.categorize(asha, "fluxomat bottle big", null);
		assertThat(variant.category()).isEqualTo(Category.SNACKS_BEVERAGES);
		Prediction withKnownSnack = engine.categorize(asha, "fluxomat and chips", null);
		assertThat(withKnownSnack.category()).isEqualTo(Category.SNACKS_BEVERAGES);
		assertThat(withKnownSnack.confidence()).isGreaterThanOrEqualTo(0.80);
	}

	@Test
	void anotherUserIsNotAffected() {
		Prediction before = engine.categorize(bala, "juse", null);

		engine.learn(asha, "juse", Category.HEALTH_MEDICINE, true);
		engine.learn(asha, "fluxomat", Category.SNACKS_BEVERAGES, true);

		assertThat(engine.categorize(bala, "juse", null)).isEqualTo(before);
		assertThat(engine.categorize(null, "juse", null)).isEqualTo(before);
		assertThat(engine.categorize(bala, "juse tetra", null).category()).isEqualTo(Category.SNACKS_BEVERAGES);
		assertThat(engine.categorize(bala, "fluxomat", null).category()).isEqualTo(Category.OTHER);
		assertThat(store.textCount(bala)).isZero();
		assertThat(store.wordCount(bala)).isZero();
	}

	@Test
	void aLaterCorrectionReplacesAnEarlierOne() {
		engine.learn(asha, "juse", Category.HEALTH_MEDICINE, true);
		engine.learn(asha, "juse", Category.GROCERIES, true);

		assertThat(engine.categorize(asha, "juse", null).category()).isEqualTo(Category.GROCERIES);
		assertThat(engine.categorize(asha, "juse tetra", null).category()).isEqualTo(Category.GROCERIES);
		Map<String, Map<Category, Double>> weights = store.wordWeights(asha, List.of("juse"));
		assertThat(weights.get("juse").get(Category.GROCERIES)).isEqualTo(1.0);
		assertThat(weights.get("juse").get(Category.HEALTH_MEDICINE)).isEqualTo(0.5);
	}

	@Test
	void keepingTheAiChoiceIsRememberedButWeighsLess() {
		engine.learn(asha, "juse", Category.SNACKS_BEVERAGES, false);

		Prediction same = engine.categorize(asha, "juse", null);
		assertThat(same.source()).isEqualTo(PredictionSource.USER_MEMORY);
		assertThat(same.category()).isEqualTo(Category.SNACKS_BEVERAGES);
		// here the user agrees with the word list, so the item is still the canonical one
		assertThat(same.item()).isEqualTo("juice");
		assertThat(store.wordWeights(asha, List.of("juse")).get("juse").get(Category.SNACKS_BEVERAGES))
			.isEqualTo(0.25);
	}

	@Test
	void anUntouchedOtherTeachesNothingButAChosenOtherIsRespected() {
		engine.learn(asha, "blorfen", Category.OTHER, false);
		assertThat(store.textCount(asha)).isZero();

		engine.learn(asha, "dosa", Category.OTHER, true);
		Prediction prediction = engine.categorize(asha, "dosa", null);
		assertThat(prediction.category()).isEqualTo(Category.OTHER);
		assertThat(prediction.source()).isEqualTo(PredictionSource.USER_MEMORY);
		assertThat(prediction.needsReview()).isFalse();
		// OTHER is not a meaning: nothing is credited to the word, so other texts are untouched
		assertThat(store.wordCount(asha)).isZero();
		assertThat(engine.categorize(asha, "masala dosa", null).category()).isEqualTo(Category.FOOD_MEALS);
	}

	@Test
	void aWordFromALongCorrectedTextCountsOnlyALittle() {
		engine.learn(asha, "blue pen box", Category.SHOPPING, true);

		assertThat(engine.categorize(asha, "blue pen box", null).category()).isEqualTo(Category.SHOPPING);
		// "pen" alone is still stationery: one of three words is weak evidence
		assertThat(engine.categorize(asha, "pen", null).category()).isEqualTo(Category.EDUCATION_STATIONERY);
	}

	@Test
	void textsWithoutWordsCanStillBeRemembered() {
		engine.learn(asha, "misc", Category.SHOPPING, true);
		Prediction prediction = engine.categorize(asha, "misc", null);
		assertThat(prediction.category()).isEqualTo(Category.SHOPPING);
		assertThat(prediction.source()).isEqualTo(PredictionSource.USER_MEMORY);
		assertThat(engine.categorize(bala, "misc", null).category()).isEqualTo(Category.OTHER);
	}

	@Test
	void nothingToLearnFromIsIgnored() {
		engine.learn(null, "juse", Category.GROCERIES, true);
		engine.learn(asha, null, Category.GROCERIES, true);
		engine.learn(asha, "", Category.GROCERIES, true);
		engine.learn(asha, "???", Category.GROCERIES, true);
		engine.learn(asha, "juse", null, true);
		assertThat(store.textCount(asha)).isZero();
		assertThat(store.wordCount(asha)).isZero();
	}

	@Test
	void memoryPerUserIsBounded() {
		InMemoryUserMemoryStore small = new InMemoryUserMemoryStore(5, 8);
		CategorizerEngine bounded = TestEngines.withMemory(small);
		for (int i = 0; i < 50; i++) {
			bounded.learn(asha, "thing" + (char) ('a' + i % 26) + (char) ('a' + i / 26) + "q", Category.SHOPPING, true);
		}
		assertThat(small.textCount(asha)).isEqualTo(5);
		assertThat(small.wordCount(asha)).isEqualTo(8);
		// a 10,000 character text is cut before it is stored
		bounded.learn(asha, "wxyz ".repeat(2000), Category.SHOPPING, true);
		assertThat(small.textCount(asha)).isEqualTo(5);
		Optional<Category> recalled = small.recall(asha, "wxyz ".repeat(12).trim());
		assertThat(recalled).contains(Category.SHOPPING);
	}

}
