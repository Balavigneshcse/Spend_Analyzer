package com.spendanalyzer.insight.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.spendanalyzer.insight.engine.Finding.TipDraft;
import org.junit.jupiter.api.Test;

class InsightRankerTest {

	private static Finding finding(String id, InsightType type, Severity severity, double impact) {
		return Finding.of(id, type, severity, "Title", "Text.", Facts.of(), impact, 1, null);
	}

	private static List<String> ids(List<Finding> findings) {
		return findings.stream().map(Finding::id).toList();
	}

	@Test
	void mostImportantFirst() {
		List<Finding> ranked = InsightRanker.rank(List.of(finding("info", InsightType.TOP_CATEGORY, Severity.INFO, 1),
				finding("alert", InsightType.RUNWAY, Severity.ALERT, 1), finding("warn", InsightType.SMALL_LEAK, Severity.WARN, 1),
				finding("small-warn", InsightType.UNUSUAL_SPEND, Severity.WARN, 0.4)));
		assertThat(ids(ranked)).containsExactly("alert", "warn", "info", "small-warn");
	}

	@Test
	void atMostSixAndTwoPerType() {
		List<Finding> findings = new ArrayList<>();
		for (int i = 1; i <= 4; i++) {
			findings.add(finding("leak-" + i, InsightType.SMALL_LEAK, Severity.WARN, 1.0 - i * 0.05));
		}
		for (InsightType type : List.of(InsightType.RUNWAY, InsightType.FORECAST, InsightType.RECURRING,
				InsightType.TOP_CATEGORY, InsightType.CREDIT_CARD, InsightType.REVIEW_NEEDED)) {
			findings.add(finding(type.name(), type, Severity.INFO, 1));
		}
		List<Finding> shown = InsightRanker.select(InsightRanker.rank(findings));
		assertThat(shown).hasSize(6);
		assertThat(ids(shown)).startsWith("leak-1", "leak-2").doesNotContain("leak-3", "leak-4");
	}

	@Test
	void goodNewsIsKeptEvenWhenItRanksLast() {
		List<Finding> findings = new ArrayList<>();
		for (InsightType type : List.of(InsightType.RUNWAY, InsightType.FORECAST, InsightType.RECURRING,
				InsightType.TOP_CATEGORY, InsightType.CREDIT_CARD, InsightType.REVIEW_NEEDED, InsightType.SMALL_LEAK)) {
			findings.add(finding(type.name(), type, Severity.WARN, 1));
		}
		findings.add(finding("good", InsightType.POSITIVE, Severity.GOOD, 0.3));
		List<Finding> shown = InsightRanker.select(InsightRanker.rank(findings));
		assertThat(shown).hasSize(6);
		assertThat(ids(shown)).endsWith("good");
	}

	/** Money runs out, four warnings about the period at 0.80, a debt warning at 0.80 and one piece of good news. */
	private static List<Finding> overspendingMonthWithADebt() {
		List<Finding> findings = new ArrayList<>();
		findings.add(finding("no-spend-days", InsightType.POSITIVE, Severity.GOOD, 0.3));
		findings.add(finding("borrowed", InsightType.BORROWED, Severity.WARN, 1));
		findings.add(finding("leak-juice", InsightType.SMALL_LEAK, Severity.WARN, 1));
		findings.add(finding("change-shopping", InsightType.PERIOD_CHANGE, Severity.WARN, 1));
		findings.add(finding("change-overall", InsightType.PERIOD_CHANGE, Severity.WARN, 1));
		findings.add(finding("needs-wants", InsightType.NEEDS_WANTS, Severity.WARN, 1));
		findings.add(finding("runway", InsightType.RUNWAY, Severity.ALERT, 1));
		return findings;
	}

	@Test
	void aDebtWarningIsNotTheOneDroppedForGoodNews() {
		List<Finding> ranked = InsightRanker.rank(overspendingMonthWithADebt());
		// the debt ties with the four period warnings and goes ahead of them
		assertThat(ranked.get(1).priority()).isEqualTo(ranked.get(5).priority());
		assertThat(ids(ranked)).containsExactly("runway", "borrowed", "leak-juice", "change-overall", "change-shopping",
				"needs-wants", "no-spend-days");

		// six are shown: the good news takes the place of the last period warning, not of the debt
		List<Finding> shown = InsightRanker.select(ranked);
		assertThat(ids(shown)).containsExactly("runway", "borrowed", "leak-juice", "change-overall", "change-shopping",
				"no-spend-days");
	}

	@Test
	void aDebtWarningIsNotPushedOutByMoreWarningsOfTheSameWeight() {
		List<Finding> findings = overspendingMonthWithADebt();
		findings.add(finding("leak-chips", InsightType.SMALL_LEAK, Severity.WARN, 1));
		findings.add(finding("unusual-shoes", InsightType.UNUSUAL_SPEND, Severity.WARN, 1));
		findings.add(finding("credit-card", InsightType.CREDIT_CARD, Severity.WARN, 1));
		List<Finding> shown = InsightRanker.select(InsightRanker.rank(findings));
		// both facts about money owed today, in the order of their types, then the period
		assertThat(ids(shown)).containsExactly("runway", "credit-card", "borrowed", "leak-chips", "leak-juice",
				"no-spend-days");
	}

	@Test
	void owingMoneyOnlyBreaksTiesAndNeverBeatsAHigherPriority() {
		List<Finding> ranked = InsightRanker.rank(List.of(finding("top", InsightType.TOP_CATEGORY, Severity.INFO, 1),
				finding("credit-card", InsightType.CREDIT_CARD, Severity.WARN, 0.5),
				finding("leak", InsightType.SMALL_LEAK, Severity.WARN, 0.7),
				finding("borrowed", InsightType.BORROWED, Severity.INFO, 1)));
		assertThat(ids(ranked)).containsExactly("leak", "borrowed", "top", "credit-card");
	}

	@Test
	void goodNewsOfAFullTypeReplacesTheWeakerOne() {
		List<Finding> findings = List.of(finding("change-overall", InsightType.PERIOD_CHANGE, Severity.WARN, 1),
				finding("change-shopping", InsightType.PERIOD_CHANGE, Severity.WARN, 0.9),
				finding("change-food", InsightType.PERIOD_CHANGE, Severity.GOOD, 0.5));
		List<Finding> shown = InsightRanker.select(InsightRanker.rank(findings));
		assertThat(ids(shown)).containsExactly("change-overall", "change-food");
	}

	@Test
	void tipsComeFromShownFindingsFirstAndStopAtThree() {
		List<Finding> all = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			TipDraft tip = new TipDraft("Tip " + i, null, Facts.of());
			all.add(new Finding("f" + i, InsightType.values()[i], Severity.INFO, "T", "X.", Facts.of(), 1.0 - i * 0.1, tip));
		}
		List<Finding> ranked = InsightRanker.rank(all);
		List<Finding> shown = List.of(ranked.get(3), ranked.get(4));
		assertThat(InsightRanker.tips(shown, ranked)).extracting(TipDraft::text).containsExactly("Tip 4", "Tip 5", "Tip 1");
	}

}
