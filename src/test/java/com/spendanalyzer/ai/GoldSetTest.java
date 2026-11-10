package com.spendanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.spendanalyzer.ai.api.Prediction;
import com.spendanalyzer.ai.api.PredictionSource;
import com.spendanalyzer.ai.engine.CategorizerEngine;
import com.spendanalyzer.ai.lexicon.Knowledge;
import com.spendanalyzer.model.Category;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Judges the engine on the hand-labelled gold set. The numbers asserted here are the quality bar
 * of the product; the full list of results is written to {@code target/ai-gold-report.txt}.
 */
class GoldSetTest {

	/** One gold row with what each variant of the engine answered. */
	private record Outcome(GoldSet.Row row, Prediction full, Prediction lexiconOnly, Category modelOnly,
			Prediction modelOnlyWithRules) {

		boolean right() {
			return row.isRight(full.category());
		}

	}

	private static List<Outcome> outcomes;

	@BeforeAll
	static void run() throws IOException {
		CategorizerEngine full = TestEngines.full();
		CategorizerEngine lexiconOnly = TestEngines.lexiconOnly();
		CategorizerEngine modelOnly = TestEngines.modelOnly();
		outcomes = new ArrayList<>();
		for (GoldSet.Row row : GoldSet.load()) {
			outcomes.add(new Outcome(row, full.categorize(null, row.text(), row.amount()),
					lexiconOnly.categorize(null, row.text(), row.amount()),
					modelOnly.rank(row.text(), row.amount()).top(),
					modelOnly.categorize(null, row.text(), row.amount())));
		}
		writeReport();
	}

	// ------------------------------------------------------------------ the set itself

	@Test
	void goldSetIsLargeEnoughAndMixedAsPlanned() {
		List<GoldSet.Row> real = GoldSet.real();
		assertThat(real.size()).isGreaterThanOrEqualTo(200);
		assertThat(share(real, "clean")).isBetween(0.30, 0.45);
		assertThat(share(real, "typo")).isBetween(0.30, 0.45);
		assertThat(share(real, "mixed")).isBetween(0.10, 0.20);
		assertThat(share(real, "multi")).isBetween(0.07, 0.15);
		assertThat(GoldSet.subset("junk").size()).isGreaterThanOrEqualTo(14);
		// every category is asked for at least ten times
		Map<Category, Integer> perCategory = new EnumMap<>(Category.class);
		real.forEach(row -> perCategory.merge(row.expected(), 1, Integer::sum));
		for (Category category : Category.values()) {
			if (category != Category.OTHER) {
				assertThat(perCategory.getOrDefault(category, 0)).as(category.name()).isGreaterThanOrEqualTo(10);
			}
		}
		Set<String> texts = new HashSet<>();
		for (GoldSet.Row row : GoldSet.load()) {
			assertThat(texts.add(row.text())).as("repeated gold row: " + row.text()).isTrue();
		}
	}

	@Test
	void goldSetIsKeptApartFromCalibrationData() {
		Set<String> calibration = new HashSet<>();
		for (Knowledge.LabelledPhrase phrase : TestEngines.full().knowledge().calibration()) {
			calibration.add(phrase.text().toLowerCase(Locale.ROOT));
		}
		for (GoldSet.Row row : GoldSet.load()) {
			assertThat(calibration).as("gold row also used for calibration").doesNotContain(row.text());
		}
	}

	@Test
	void mostMisspeltRowsAreNotSimplyListedInTheWordLists() {
		// a misspelling that is itself a listed spelling tests the list, not the spelling repair
		List<Outcome> typos = of("typo");
		long listed = typos.stream().filter(o -> o.full().source() == PredictionSource.LEXICON_EXACT).count();
		System.out.printf("gold: %d of %d misspelt rows are answered by an exact list entry%n", listed, typos.size());
		assertThat(listed / (double) typos.size()).isLessThan(0.20);
	}

	// ------------------------------------------------------------------ accuracy

	@Test
	void topOneAccuracyOverall() {
		assertThat(accuracy(real())).isGreaterThanOrEqualTo(0.90);
	}

	@Test
	void topOneAccuracyBySubset() {
		assertThat(accuracy(of("clean"))).as("clean").isGreaterThanOrEqualTo(0.97);
		assertThat(accuracy(of("typo"))).as("misspelt").isGreaterThanOrEqualTo(0.85);
		assertThat(accuracy(of("mixed"))).as("Tanglish / Hinglish").isGreaterThanOrEqualTo(0.80);
		assertThat(accuracy(of("multi"))).as("multi-word / digits").isGreaterThanOrEqualTo(0.85);
	}

	@Test
	void theRightCategoryIsAmongTheTopThree() {
		List<Outcome> real = real();
		long hits = real.stream().filter(o -> {
			if (o.right()) {
				return true;
			}
			return o.full().alternatives().stream().anyMatch(a -> o.row().isRight(a.category()));
		}).count();
		assertThat(hits / (double) real.size()).isGreaterThanOrEqualTo(0.97);
	}

	@Test
	void everyCategoryIsRecalled() {
		Map<Category, int[]> perCategory = new EnumMap<>(Category.class);
		for (Outcome outcome : real()) {
			int[] counts = perCategory.computeIfAbsent(outcome.row().expected(), k -> new int[2]);
			counts[0]++;
			if (outcome.right()) {
				counts[1]++;
			}
		}
		perCategory.forEach((category, counts) -> assertThat(counts[1] / (double) counts[0]).as(category.name())
			.isGreaterThanOrEqualTo(0.75));
	}

	// ------------------------------------------------------------------ confidence

	@Test
	void confidentAnswersAreAlmostAlwaysRight() {
		List<Outcome> confident = real().stream().filter(o -> o.full().confidence() >= CategorizerEngine.ACCEPT).toList();
		long right = confident.stream().filter(Outcome::right).count();
		// the confident answers must be the bulk of all answers, or the precision means nothing
		assertThat(confident.size() / (double) real().size()).isGreaterThanOrEqualTo(0.80);
		assertThat(right / (double) confident.size()).isGreaterThanOrEqualTo(0.95);
	}

	@Test
	void confidenceIsCalibrated() {
		assertThat(expectedCalibrationError(real())).isLessThanOrEqualTo(0.08);
	}

	@Test
	void junkIsSentToReview() {
		List<Outcome> junk = of("junk");
		long refused = junk.stream()
			.filter(o -> o.full().category() == Category.OTHER && o.full().needsReview())
			.count();
		assertThat(refused / (double) junk.size()).isGreaterThanOrEqualTo(0.90);
		// and none of it is ever accepted silently
		assertThat(junk).allMatch(o -> o.full().needsReview());
	}

	// ------------------------------------------------------------------ what each layer adds

	@Test
	void theLearnedModelAloneCarriesRealSignal() {
		long right = real().stream().filter(o -> o.row().isRight(o.modelOnly())).count();
		assertThat(right / (double) real().size()).isGreaterThanOrEqualTo(0.70);
	}

	@Test
	void theEnsembleBeatsTheWordListsOnMisspellings() {
		List<Outcome> typos = of("typo");
		long ensemble = typos.stream().filter(Outcome::right).count();
		long lexiconOnly = typos.stream().filter(o -> o.row().isRight(o.lexiconOnly().category())).count();
		assertThat(ensemble).isGreaterThan(lexiconOnly);
		// and it never does worse overall
		long allEnsemble = real().stream().filter(Outcome::right).count();
		long allLexicon = real().stream().filter(o -> o.row().isRight(o.lexiconOnly().category())).count();
		assertThat(allEnsemble).isGreaterThanOrEqualTo(allLexicon);
	}

	// ------------------------------------------------------------------ helpers

	private static List<Outcome> real() {
		return outcomes.stream().filter(o -> !o.row().isJunk()).toList();
	}

	private static List<Outcome> of(String subset) {
		return outcomes.stream().filter(o -> o.row().subset().equals(subset)).toList();
	}

	private static double accuracy(List<Outcome> list) {
		return list.stream().filter(Outcome::right).count() / (double) list.size();
	}

	private static double share(List<GoldSet.Row> rows, String subset) {
		return rows.stream().filter(row -> row.subset().equals(subset)).count() / (double) rows.size();
	}

	/**
	 * Ten equal-width confidence bins; in each, |average confidence - share that was right|,
	 * weighted by how many answers fell into the bin.
	 */
	private static double expectedCalibrationError(List<Outcome> list) {
		double[] confidence = new double[10];
		int[] right = new int[10];
		int[] count = new int[10];
		for (Outcome outcome : list) {
			int bin = Math.min(9, (int) (outcome.full().confidence() * 10));
			confidence[bin] += outcome.full().confidence();
			count[bin]++;
			if (outcome.right()) {
				right[bin]++;
			}
		}
		double error = 0;
		for (int bin = 0; bin < 10; bin++) {
			if (count[bin] > 0) {
				error += Math.abs(confidence[bin] / count[bin] - right[bin] / (double) count[bin]) * count[bin];
			}
		}
		return error / list.size();
	}

	private static void writeReport() throws IOException {
		Path file = Path.of("target", "ai-gold-report.txt");
		Files.createDirectories(file.getParent());
		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
			List<Outcome> real = real();
			out.printf(Locale.ROOT, "gold rows: %d real + %d junk%n", real.size(), of("junk").size());
			for (String subset : List.of("clean", "typo", "mixed", "multi")) {
				List<Outcome> list = of(subset);
				out.printf(Locale.ROOT, "%-6s n=%3d  ensemble %.3f  lexicon-only %.3f  model-only %.3f%n", subset,
						list.size(), accuracy(list),
						list.stream().filter(o -> o.row().isRight(o.lexiconOnly().category())).count()
								/ (double) list.size(),
						list.stream().filter(o -> o.row().isRight(o.modelOnly())).count() / (double) list.size());
			}
			out.printf(Locale.ROOT, "ALL    n=%3d  ensemble %.3f  lexicon-only %.3f  model-only %.3f%n", real.size(),
					accuracy(real),
					real.stream().filter(o -> o.row().isRight(o.lexiconOnly().category())).count() / (double) real.size(),
					real.stream().filter(o -> o.row().isRight(o.modelOnly())).count() / (double) real.size());
			List<Outcome> confident = real.stream()
				.filter(o -> o.full().confidence() >= CategorizerEngine.ACCEPT)
				.toList();
			out.printf(Locale.ROOT, "confidence >= 0.80: %d of %d answers, precision %.3f%n", confident.size(),
					real.size(), confident.stream().filter(Outcome::right).count() / (double) confident.size());
			out.printf(Locale.ROOT, "model-only when its guesses must also pass the abstain rules: %.3f%n",
					real.stream().filter(o -> o.row().isRight(o.modelOnlyWithRules().category())).count()
							/ (double) real.size());
			out.printf(Locale.ROOT, "expected calibration error: %.3f%n", expectedCalibrationError(real));
			List<Outcome> junk = of("junk");
			out.printf(Locale.ROOT, "junk sent to OTHER: %d of %d%n",
					junk.stream().filter(o -> o.full().category() == Category.OTHER).count(), junk.size());
			Map<PredictionSource, Integer> sources = new EnumMap<>(PredictionSource.class);
			real.forEach(o -> sources.merge(o.full().source(), 1, Integer::sum));
			out.println("answered by: " + sources);
			out.println();
			out.println("wrong, unsure or rescued by the model:");
			for (Outcome o : outcomes) {
				boolean lexiconRight = o.row().isRight(o.lexiconOnly().category());
				if (!o.right() || (!o.row().isJunk() && (o.full().needsReview() || !lexiconRight))) {
					out.printf(Locale.ROOT, "%-5s %-6s %-28s want %-21s got %-21s %.3f %-13s item=[%s] lexicon-only=%s model-only=%s%n",
							o.right() ? (o.full().needsReview() ? "low" : "ok") : "MISS", o.row().subset(),
							o.row().text(), o.row().expected(), o.full().category(), o.full().confidence(),
							o.full().source(), o.full().item(), o.lexiconOnly().category(), o.modelOnly());
				}
			}
		}
	}

}
