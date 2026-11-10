package com.spendanalyzer.insight.engine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

	/**
	 * The days of the period on which the user was already tracking: the period, but not its days
	 * before the first expense ever. Every per-day figure divides by this, so somebody who started
	 * part-way through the period does not get an average spread over days without a notebook (the
	 * daily burn clips its window the same way). Never less than 1.
	 */
	int trackedDays() {
		PeriodWindow period = in.period();
		LocalDate from = period.from();
		LocalDate since = in.trackingSince();
		if (since != null && since.isAfter(from) && !since.isAfter(period.to())) {
			from = since;
		}
		return (int) Math.max(1, ChronoUnit.DAYS.between(from, period.to()) + 1);
	}

	/**
	 * True in the first days of a week or month to date. Today is still unfinished while the same
	 * day of the previous period is a full day, so this early the comparison leans towards "down":
	 * a "less than" claim is withheld, a "more than" claim is safe (today can only grow).
	 */
	boolean tooEarlyForDown() {
		return endsToday() && in.period().days() < Guardrails.TO_DATE_DOWN_MIN_DAYS;
	}

}
