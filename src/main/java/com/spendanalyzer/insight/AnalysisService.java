package com.spendanalyzer.insight;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.spendanalyzer.insight.engine.Analysis;
import com.spendanalyzer.insight.engine.Balances;
import com.spendanalyzer.insight.engine.Expense;
import com.spendanalyzer.insight.engine.InsightEngine;
import com.spendanalyzer.insight.engine.LedgerSnapshot;
import com.spendanalyzer.insight.engine.PeriodCode;
import com.spendanalyzer.insight.engine.PeriodWindow;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Loads one user's expenses and balances and hands them to the {@link InsightEngine}. All the
 * thinking is in the engine; this class only decides which dates to load.
 */
@Service
public class AnalysisService {

	private final AnalysisRepository repository;

	private final Clock clock;

	private final ZoneId zone;

	private final int maxRows;

	private final InsightEngine engine = new InsightEngine();

	public AnalysisService(AnalysisRepository repository, Clock clock, @Qualifier("appZone") ZoneId appZone,
			@Value("${app.analysis.max-rows:20000}") int maxRows) {
		this.repository = repository;
		this.clock = clock;
		this.zone = appZone;
		this.maxRows = Math.max(1, maxRows);
	}

	public Analysis analyse(UUID userId, PeriodCode code) {
		Instant now = clock.instant();
		LocalDate today = PeriodWindow.today(clock, zone);
		LocalDate firstExpense = repository.firstExpenseDate(userId, today);
		PeriodWindow window = PeriodWindow.resolve(code, today, firstExpense);

		// One query covers the period, the period before it and the history for the "today" blocks.
		LocalDate from = LedgerSnapshot.historyStart(today);
		if (window.from().isBefore(from)) {
			from = window.from();
		}
		if (window.hasPrevious() && window.previousFrom().isBefore(from)) {
			from = window.previousFrom();
		}
		List<Expense> rows = repository.expenses(userId, from, today, maxRows + 1);
		if (rows.size() > maxRows) {
			// Only a very long ALL_TIME ledger gets here. Keep the newest rows, drop the oldest day
			// (it is only partly loaded) and let the period start where the loaded data starts.
			LocalDate cut = rows.get(rows.size() - 1).date();
			List<Expense> kept = rows.stream().filter(row -> row.date().isAfter(cut)).toList();
			if (!kept.isEmpty()) {
				rows = kept;
				if (window.from().isBefore(cut.plusDays(1))) {
					window = new PeriodWindow(window.code(), cut.plusDays(1), window.to(), window.previousFrom(),
							window.previousTo());
				}
			}
			else {
				rows = rows.subList(0, maxRows);
			}
		}
		Balances balances = repository.balances(userId);
		// Stable per user, so the wording of the summary does not change on refresh.
		long seed = userId.getMostSignificantBits() ^ userId.getLeastSignificantBits();
		return engine.analyse(LedgerSnapshot.of(window, today, rows, firstExpense, balances, seed, now));
	}

}
