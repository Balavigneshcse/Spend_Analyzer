package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.spendanalyzer.insight.engine.Tally.Bucket;
import com.spendanalyzer.model.Category;

/**
 * Writes the summary: two to five plain sentences made from templates and the computed numbers.
 * Total and pace, the top category, the comparison with the previous period when it is valid, and
 * the most important insight. With fewer than three expenses it is one friendly line.
 */
final class SummaryWriter {

	/** The comparison and the top category already have their own sentence. */
	private static final Set<String> ALREADY_SAID = Set.of("change-overall", "positive-spending-down", "top-category");

	private static final int MAX_SENTENCES = 5;

	record Summary(String text, Facts facts) {
	}

	private SummaryWriter() {
	}

	static Summary write(Picture p, List<Finding> shown) {
		Tally now = p.now();
		Wording words = p.words();
		int count = now.count();
		Facts facts = Facts.of()
			.text("when", words.when())
			.count("count", count)
			.text("spends", Wording.plural(count, "spend", "spends"))
			.rupees("total", now.total());
		if (count == 0) {
			return new Summary(facts.fill(p.endsToday()
					? "No spends recorded {when} yet; add a few and your analysis will appear here."
					: "No spends were recorded {when}, so there is nothing to analyse for it."), facts);
		}
		if (count < Guardrails.SUMMARY_MIN_EXPENSES) {
			return new Summary(facts.fill(p.endsToday()
					? "You have recorded {count} {spends} {when} ({total} in total); add a few more to unlock the full analysis."
					: "Only {count} {spends} ({total} in total) were recorded {when}, too few for an analysis."), facts);
		}

		List<String> sentences = new ArrayList<>();
		boolean early = Guardrails.nearMinimum(count, Guardrails.MIN_EXPENSES);
		BigDecimal perDay = now.total().divide(BigDecimal.valueOf(p.period().days()), 2, RoundingMode.HALF_UP);
		facts.about("perDay", perDay);
		if (early) {
			sentences.add(p.endsToday()
					? "So far there are {count} purchases {when}: {total} in total, about {perDay} a day."
					: "Only {count} purchases were recorded {when}: {total} in total, about {perDay} a day.");
		}
		else {
			sentences.add(words.pick("summary-total",
					"You spent {total} on {count} purchases {when}, about {perDay} a day.",
					"Your spending {when} comes to {total} over {count} purchases, about {perDay} a day.",
					"{count} purchases {when} add up to {total}, about {perDay} a day."));
		}
		sentences.add(topCategory(now, words, facts));
		String comparison = comparison(p, facts);
		if (comparison != null) {
			sentences.add(comparison);
		}
		StringBuilder text = new StringBuilder(facts.fill(String.join(" ", sentences)));
		int room = MAX_SENTENCES - sentences.size() - (early ? 1 : 0);
		for (Finding finding : shown) {
			if (!ALREADY_SAID.contains(finding.id()) && sentenceCount(finding.text()) <= room) {
				// the most important insight that fits, in its own words
				text.append(' ').append(finding.text());
				facts.merge(finding.facts());
				break;
			}
		}
		if (early) {
			text.append(" With more entries the picture will get sharper.");
		}
		return new Summary(text.toString(), facts);
	}

	/** Sentences end with ". " inside a text; amounts like ₹107.50 have no space after the dot. */
	private static int sentenceCount(String text) {
		int count = 1;
		for (int at = text.indexOf(". "); at >= 0; at = text.indexOf(". ", at + 1)) {
			count++;
		}
		return count;
	}

	private static String topCategory(Tally now, Wording words, Facts facts) {
		var top = now.categoriesBySize().get(0);
		Category category = top.getKey();
		Bucket bucket = top.getValue();
		facts.text("category", category.label()).rupees("categoryAmount", bucket.amount());
		if (now.byCategory().size() == 1) {
			return "All of it went on {category}.";
		}
		if (!Guardrails.percentAllowed(now.total(), now.count())) {
			return "The biggest share went on {category}: {categoryAmount}.";
		}
		facts.percent("categoryShare", Num.percentOf(bucket.amount(), now.total()));
		return words.pick("summary-top", "{category} took the biggest share: {categoryAmount}, {categoryShare} of the total.",
				"The biggest share went on {category}: {categoryAmount}, {categoryShare} of the total.");
	}

	private static String comparison(Picture p, Facts facts) {
		Comparison comparison = p.comparison();
		if (!comparison.speakable()) {
			return null;
		}
		double percent = comparison.changePercent();
		facts.percent("change", percent).rupees("previousTotal", comparison.previousSpent()).text("previous", p.words().previous());
		if (percent >= 5) {
			return "That is {change} more than in {previous} ({previousTotal}).";
		}
		if (percent <= -5) {
			return "That is {change} less than in {previous} ({previousTotal}).";
		}
		return "That is close to {previous} ({previousTotal}).";
	}

}
