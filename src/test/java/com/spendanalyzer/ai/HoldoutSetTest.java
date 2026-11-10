package com.spendanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.spendanalyzer.ai.api.Prediction;
import com.spendanalyzer.ai.engine.CategorizerEngine;
import com.spendanalyzer.ai.lexicon.Knowledge;
import com.spendanalyzer.model.Category;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A second opinion on the engine: {@code src/test/resources/ai/holdout_phrases.tsv} was written
 * blind, by somebody who had not seen the engine, its word lists or the gold set. Measured for the
 * first time, before anything was changed because of it, the engine got 259 of the 265 real
 * phrases right (97.7%) and refused all 10 junk rows.
 *
 * <p>Six misses were looked at afterwards and the knowledge behind them was added to the word lists
 * (a fine is a fee, "gaadi" is a vehicle, ironing is laundry, money sent home is a transfer), so
 * the list is no longer blind for those. It stays a regression bar: the bounds below are well
 * under the first blind measurement on purpose, so a retrained model that tips one borderline
 * phrase does not fail the build, while a real loss of knowledge does.
 *
 * <p>Columns (TAB separated): text | expected CATEGORY | other acceptable CATEGORIES, comma
 * separated (may be empty) | kind (clean, typo, mixed, multi or junk).
 */
class HoldoutSetTest {

	private static final String RESOURCE = "ai/holdout_phrases.tsv";

	/** One phrase with every category a reasonable person could file it under. */
	private record Row(String text, Set<Category> acceptable, String kind, Prediction answer) {

		boolean isJunk() {
			return kind.equals("junk");
		}

		boolean right() {
			return acceptable.contains(answer.category());
		}

		/** Accepted without asking the user: what ends up in the ledger unseen. */
		boolean silent() {
			return answer.confidence() >= CategorizerEngine.ACCEPT && !answer.needsReview();
		}

		@Override
		public String toString() {
			return String.format(Locale.ROOT, "%s (%s): want %s, got %s at %.2f%s", text, kind, acceptable,
					answer.category(), answer.confidence(), answer.needsReview() ? ", flagged" : "");
		}

	}

	private static List<Row> rows;

	@BeforeAll
	static void run() {
		CategorizerEngine engine = TestEngines.full();
		rows = new ArrayList<>();
		for (Knowledge.Line line : Knowledge.lines(RESOURCE)) {
			String[] columns = line.text().split("\t");
			if (columns.length != 4) {
				throw new IllegalStateException(RESOURCE + " line " + line.number() + ": need text, category, others, kind");
			}
			Set<Category> acceptable = EnumSet.of(Category.valueOf(columns[1].trim()));
			for (String other : columns[2].split(",")) {
				if (!other.isBlank()) {
					acceptable.add(Category.valueOf(other.trim()));
				}
			}
			// no amount: the list has none, so the text alone must carry the answer
			rows.add(new Row(columns[0], acceptable, columns[3].trim(), engine.categorize(null, columns[0], null)));
		}
	}

	@Test
	void theListIsWhatWasHandedOver() {
		assertThat(real()).hasSize(265);
		assertThat(junk()).hasSize(10);
		Set<String> texts = new HashSet<>();
		for (Row row : rows) {
			assertThat(texts.add(row.text())).as("repeated row: " + row.text()).isTrue();
			assertThat(row.kind()).isIn("clean", "typo", "mixed", "multi", "junk");
		}
		assertThat(junk()).allMatch(row -> row.acceptable().equals(EnumSet.of(Category.OTHER)));
	}

	@Test
	void realPhrasesGetAnAcceptableCategory() {
		List<Row> real = real();
		List<Row> wrong = real.stream().filter(row -> !row.right()).toList();
		System.out.printf(Locale.ROOT, "holdout: %d of %d real phrases right (%.1f%%); missed: %s%n",
				real.size() - wrong.size(), real.size(), 100.0 * (real.size() - wrong.size()) / real.size(), wrong);
		assertThat((real.size() - wrong.size()) / (double) real.size()).as("missed: %s", wrong)
			.isGreaterThanOrEqualTo(0.95);
	}

	@Test
	void junkIsRefused() {
		List<Row> junk = junk();
		List<Row> accepted = junk.stream()
			.filter(row -> row.answer().category() != Category.OTHER || !row.answer().needsReview())
			.toList();
		assertThat((junk.size() - accepted.size()) / (double) junk.size()).as("not refused: %s", accepted)
			.isGreaterThanOrEqualTo(0.90);
		// and none of it is ever accepted silently
		assertThat(junk).allMatch(row -> row.answer().needsReview());
	}

	@Test
	void whatIsAcceptedWithoutAskingIsAlmostAlwaysRight() {
		List<Row> silent = real().stream().filter(Row::silent).toList();
		List<Row> wrong = silent.stream().filter(row -> !row.right()).toList();
		// the silent answers must be the bulk of all answers, or the precision means nothing
		assertThat(silent.size() / (double) real().size()).isGreaterThanOrEqualTo(0.80);
		assertThat((silent.size() - wrong.size()) / (double) silent.size()).as("confidently wrong: %s", wrong)
			.isGreaterThanOrEqualTo(0.97);
	}

	private static List<Row> real() {
		return rows.stream().filter(row -> !row.isJunk()).toList();
	}

	private static List<Row> junk() {
		return rows.stream().filter(Row::isJunk).toList();
	}

}
