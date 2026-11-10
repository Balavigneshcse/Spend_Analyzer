package com.spendanalyzer.insight.engine;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.spendanalyzer.common.Money;
import com.spendanalyzer.insight.engine.BurnModel.Burn;
import com.spendanalyzer.insight.engine.Finding.TipDraft;
import com.spendanalyzer.insight.engine.RecurringDetector.Recurring;

/**
 * The hand-written insight engine. Pure: plain data in ({@link LedgerSnapshot}), the finished
 * {@link Analysis} out. No database, no clock, no library: its own statistics ({@link Stats}),
 * its own detectors and its own template-based sentences.
 * <p>
 * How it works: sum up the period ({@link Breakdown}); judge the situation today from the recent
 * history (recurring bills, unusual spends, daily burn, runway, forecast, health score); let every
 * detector report a {@link Finding} if its minimum-data rule in {@link Guardrails} is met; rank
 * them, keep the best six, and write the tips and the summary from those findings.
 */
public final class InsightEngine {

	/**
	 * @param analysis the response
	 * @param rupeeFacts every rupee amount, formatted as in a sentence, that a shown insight, tip
	 * or the summary was allowed to print. Tests check that no sentence shows any other figure
	 */
	public record Report(Analysis analysis, Set<String> rupeeFacts) {
	}

	public Analysis analyse(LedgerSnapshot in) {
		return report(in).analysis();
	}

	public Report report(LedgerSnapshot in) {
		PeriodWindow window = in.period();
		Tally now = Tally.of(in.expenses());
		Tally before = Tally.of(in.previous());
		Comparison comparison = Comparison.of(in, now, before);

		// The situation today, from the recent history and the current balances.
		List<Recurring> recurring = RecurringDetector.detect(in.history(), in.today());
		AnomalyDetector.Result anomalies = AnomalyDetector.detect(in.history(), recurring);
		Burn burn = BurnModel.estimate(in.history(), in.today(), recurring);
		RunwayCalc.Result runway = RunwayCalc.of(burn, in.balances(), in.today());
		ForecastCalc.Result forecast = ForecastCalc.of(in.history(), in.today(), burn);
		Analysis.Health health = HealthScorer.score(in, runway, anomalies);

		Picture picture = new Picture(in, now, before, comparison, recurring, anomalies, burn, runway, forecast,
				new Wording(window, in.variantSeed()));
		List<Finding> findings = new ArrayList<>();
		PeriodFindings.addAll(picture, findings);
		if (picture.endsToday()) {
			TodayFindings.addAll(picture, findings);
		}
		List<Finding> ranked = InsightRanker.rank(findings);
		List<Finding> shown = InsightRanker.select(ranked);
		List<TipDraft> tips = InsightRanker.tips(shown, ranked);
		SummaryWriter.Summary summary = SummaryWriter.write(picture, shown);

		Set<String> rupeeFacts = new LinkedHashSet<>(summary.facts().rupeeStrings());
		List<Analysis.Insight> insights = new ArrayList<>();
		for (Finding finding : shown) {
			insights.add(new Analysis.Insight(finding.id(), finding.type(), finding.severity(), finding.title(),
					finding.text()));
			rupeeFacts.addAll(finding.facts().rupeeStrings());
		}
		List<Analysis.Tip> tipLines = new ArrayList<>();
		for (TipDraft tip : tips) {
			tipLines.add(new Analysis.Tip(tip.text(), Money.format(tip.saving())));
			rupeeFacts.addAll(tip.facts().rupeeStrings());
		}

		Analysis analysis = new Analysis(Breakdown.period(window), Breakdown.totals(now, picture.trackedDays(), comparison),
				Breakdown.byCategory(now), Breakdown.byMode(now), Breakdown.daily(now, window), Breakdown.topItems(now),
				Breakdown.needsWants(now), Breakdown.runway(runway), Breakdown.forecast(forecast), health,
				List.copyOf(insights), List.copyOf(tipLines), summary.text(), dataQuality(picture),
				in.generatedAt().truncatedTo(ChronoUnit.SECONDS).toString());
		return new Report(analysis, rupeeFacts);
	}

	/** Says honestly when there is too little to go on, and what would help. */
	private static Analysis.DataQuality dataQuality(Picture p) {
		Tally now = p.now();
		int count = now.count();
		int toReview = now.needsReview().count();
		boolean enough = count >= Guardrails.MIN_EXPENSES;
		String message = null;
		if (!enough && !p.endsToday()) {
			message = count == 0 ? "No spends recorded " + p.words().when()
					: "Too few spends " + p.words().when() + " to show trends";
		}
		else if (count == 0) {
			message = "Add your first spend to start the analysis";
		}
		else if (!enough) {
			int missing = Guardrails.MIN_EXPENSES - count;
			message = "Add " + missing + " more " + Wording.plural(missing, "spend", "spends") + " to unlock trends";
		}
		else if (toReview > 0) {
			message = "Check the category of " + toReview + " " + Wording.plural(toReview, "spend", "spends")
					+ " for a sharper analysis";
		}
		return new Analysis.DataQuality(toReview, enough, message);
	}

}
