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
