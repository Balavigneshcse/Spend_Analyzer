package com.spendanalyzer.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.spendanalyzer.ai.lexicon.Knowledge;
import com.spendanalyzer.model.Category;

/** Reads {@code src/test/resources/ai/gold_phrases.tsv}: the hand-labelled phrases the engine is judged on. */
final class GoldSet {

	/**
	 * @param subset clean, typo, mixed, multi or junk
	 * @param alsoRight a second category that a reasonable person could also pick, or null
	 */
	record Row(String text, Category expected, String subset, BigDecimal amount, Category alsoRight) {

		boolean isRight(Category predicted) {
			return predicted == expected || (alsoRight != null && predicted == alsoRight);
		}

		boolean isJunk() {
			return subset.equals("junk");
		}

	}

	private GoldSet() {
	}

	static List<Row> load() {
		List<Row> rows = new ArrayList<>();
		for (Knowledge.Line line : Knowledge.lines("ai/gold_phrases.tsv")) {
			String[] columns = line.text().split("\t");
			if (columns.length < 3) {
				throw new IllegalStateException("gold_phrases.tsv line " + line.number() + ": need text, category, subset");
			}
			BigDecimal amount = columns.length > 3 && !columns[3].isBlank() ? new BigDecimal(columns[3].trim()) : null;
			Category also = columns.length > 4 && !columns[4].isBlank() ? Category.valueOf(columns[4].trim()) : null;
			rows.add(new Row(columns[0], Category.valueOf(columns[1].trim()), columns[2].trim(), amount, also));
		}
		return rows;
	}

	/** The rows that name a real spend (everything except junk). */
	static List<Row> real() {
		return load().stream().filter(row -> !row.isJunk()).toList();
	}

	static List<Row> subset(String name) {
		return load().stream().filter(row -> row.subset().equals(name)).toList();
	}

}
