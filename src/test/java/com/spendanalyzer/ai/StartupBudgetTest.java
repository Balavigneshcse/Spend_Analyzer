package com.spendanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.spendanalyzer.ai.engine.CategorizerEngine;
import com.spendanalyzer.ai.learn.UserMemoryStore;
import org.junit.jupiter.api.Test;

/**
 * The start-up and speed budget (the production server has 0.1 CPU and 512 MB). The measured
 * numbers are printed and written to {@code target/ai-budget-report.txt}; the asserted bounds are
 * loose on purpose so a busy build machine does not fail the build.
 */
class StartupBudgetTest {

	@Test
	void loadingTrainingAndPredictingStayWithinBudget() throws IOException {
		// 1. constructing: reads the word lists, nothing more
		long started = System.nanoTime();
		CategorizerEngine engine = CategorizerEngine.create(UserMemoryStore.NONE);
		long constructMillis = millisSince(started);
		assertThat(engine.isModelReady()).as("construction must not train").isFalse();

		// the spelling index is built on first use; in the application the background thread does it
		started = System.nanoTime();
		assertThat(engine.categorize(null, "juse", null).item()).isEqualTo("juice");
		long firstAnswerMillis = millisSince(started);

		// 2. training starts in the background and returns at once
		started = System.nanoTime();
		engine.startTraining();
		long startTrainingMillis = millisSince(started);
		// while it trains, the word lists already answer
		assertThat(engine.categorize(null, "bus ticket", null).confidence()).isGreaterThanOrEqualTo(0.80);
		assertThat(engine.awaitModel(Duration.ofSeconds(60))).isTrue();
		CategorizerEngine.Stats background = engine.stats();

		// 3. training once more on this (now warm) thread, as a second measurement
		long warmTrainMillis = engine.trainNow().millis();

		// 4. predictions after warm-up
		List<String> texts = List.of("juse", "briyany", "bus ticket", "pen", "recharge 199", "amazon earphone",
				"kothu parotta 2", "curent bil", "movie tkt", "asdf", "paid for chiken biryani at hotel",
				"some long unknown thing typed here");
		for (int i = 0; i < 20_000; i++) {
			engine.categorize(null, texts.get(i % texts.size()), BigDecimal.TEN);
		}
		int rounds = 20_000;
		long[] nanos = new long[rounds];
		for (int i = 0; i < rounds; i++) {
			// a never-seen word in every other call, so the spelling cache cannot hide the real cost
			String text = i % 2 == 0 ? texts.get(i % texts.size()) : "biryanee" + i + " tiffn";
			long before = System.nanoTime();
			engine.categorize(null, text, BigDecimal.TEN);
			nanos[i] = System.nanoTime() - before;
		}
		Arrays.sort(nanos);
		double averageMicros = Arrays.stream(nanos).average().orElse(0) / 1000.0;
		double p99Micros = nanos[(int) (rounds * 0.99)] / 1000.0;

		String report = String.format(Locale.ROOT,
				"construct (reads the word lists): %d ms%n"
						+ "first answer, which builds the spelling index: %d ms%n"
						+ "startTraining() returned after: %d ms%n"
						+ "background training: %d ms for %d examples%n" + "training again, warm: %d ms%n"
						+ "model weights: %d KB, temperature %.1f%n"
						+ "word lists: %d items, %d spellings, %d words in the spelling index%n"
						+ "one prediction after warm-up: average %.1f microseconds, 99th percentile %.1f%n",
				constructMillis, firstAnswerMillis, startTrainingMillis, background.trainMillis(), background.trainingExamples(),
				warmTrainMillis, background.modelBytes() / 1024, background.temperature(), background.items(),
				background.terms(), background.vocabulary(), averageMicros, p99Micros);
		System.out.print(report);
		Files.createDirectories(Path.of("target"));
		Files.writeString(Path.of("target", "ai-budget-report.txt"), report, StandardCharsets.UTF_8);

		assertThat(startTrainingMillis).as("startTraining must not block").isLessThan(100);
		assertThat(constructMillis).as("constructing").isLessThan(5_000);
		assertThat(background.trainMillis()).as("training").isLessThan(10_000);
		assertThat(background.modelBytes()).as("model memory").isLessThanOrEqualTo(8L * 1024 * 1024);
		assertThat(averageMicros).as("average prediction time").isLessThan(2_000.0);
		assertThat(p99Micros).as("99th percentile prediction time").isLessThan(5_000.0);
	}

	private static long millisSince(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}

}
