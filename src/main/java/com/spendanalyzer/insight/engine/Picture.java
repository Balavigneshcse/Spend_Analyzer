package com.spendanalyzer.insight.engine;

import java.util.List;

import com.spendanalyzer.insight.engine.BurnModel.Burn;
import com.spendanalyzer.insight.engine.RecurringDetector.Recurring;

/** Everything computed once and shared by the detectors: the input plus the derived numbers. */
record Picture(LedgerSnapshot in, Tally now, Tally before, Comparison comparison, List<Recurring> recurring,
		AnomalyDetector.Result anomalies, Burn burn, RunwayCalc.Result runway, ForecastCalc.Result forecast,
		Wording words) {

	PeriodWindow period() {
		return in.period();
	}

	/** True when the chosen period runs up to today (everything except "last month"). */
	boolean endsToday() {
		return in.period().to().equals(in.today());
	}

}
