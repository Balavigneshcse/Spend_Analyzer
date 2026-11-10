package com.spendanalyzer.insight.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Everything the insight engine needs, as plain data: no database, no clock, no Spring.
 *
 * @param period the chosen period and the period it is compared with
 * @param today today's date in the user's calendar
 * @param expenses the expenses inside the period
 * @param previous the expenses inside the previous like-for-like period (empty when there is none)
 * @param history the expenses of the last {@value #HISTORY_DAYS} days up to today; feeds the
 * "today" blocks (runway, forecast, health) whatever period was chosen
 * @param trackingSince date of the user's first expense ever, null when there is none. A comparison
 * with a period from before this date would be a comparison with an empty notebook
 * @param balances current balances
 * @param debts what the user owes friends right now, one row per friend still owed money. Borrowing
 * and paying back are neither spending nor income, so they appear nowhere else in the input
 * @param variantSeed stable per user; picks between equivalent wordings
 * @param generatedAt time stamp to print on the analysis
 */
public record LedgerSnapshot(PeriodWindow period, LocalDate today, List<Expense> expenses, List<Expense> previous,
		List<Expense> history, LocalDate trackingSince, Balances balances, List<Debt> debts, long variantSeed,
		Instant generatedAt) {

	public static final int HISTORY_DAYS = 120;

	public LedgerSnapshot {
		// Sorted copies: the engine's answers must not depend on the order of the input rows.
		expenses = sorted(expenses);
		previous = sorted(previous);
		history = sorted(history);
		balances = balances == null ? Balances.NONE : balances;
		debts = owed(debts);
	}

	/** A user who owes nobody anything. */
	public static LedgerSnapshot of(PeriodWindow period, LocalDate today, Collection<Expense> rows,
			LocalDate trackingSince, Balances balances, long variantSeed, Instant generatedAt) {
		return of(period, today, rows, trackingSince, balances, List.of(), variantSeed, generatedAt);
	}

	/** Splits one bag of expense rows into the period, the previous period and the recent history. */
	public static LedgerSnapshot of(PeriodWindow period, LocalDate today, Collection<Expense> rows,
			LocalDate trackingSince, Balances balances, List<Debt> debts, long variantSeed, Instant generatedAt) {
		List<Expense> current = new ArrayList<>();
		List<Expense> previous = new ArrayList<>();
		List<Expense> history = new ArrayList<>();
		LocalDate historyStart = historyStart(today);
		for (Expense row : rows) {
			LocalDate date = row.date();
			if (period.contains(date)) {
				current.add(row);
			}
			if (period.previousContains(date)) {
				previous.add(row);
			}
			if (!date.isBefore(historyStart) && !date.isAfter(today)) {
				history.add(row);
			}
		}
		return new LedgerSnapshot(period, today, current, previous, history, trackingSince, balances, debts,
				variantSeed, generatedAt);
	}

	public static LocalDate historyStart(LocalDate today) {
		return today.minusDays(HISTORY_DAYS - 1L);
	}

	/** Only friends who are really owed something, the largest debt first. */
	private static List<Debt> owed(List<Debt> debts) {
		if (debts == null) {
			return List.of();
		}
		List<Debt> copy = new ArrayList<>();
		for (Debt debt : debts) {
			if (debt.owed().signum() > 0) {
				copy.add(debt);
			}
		}
		copy.sort(Debt.LARGEST_FIRST);
		return List.copyOf(copy);
	}

	private static List<Expense> sorted(List<Expense> rows) {
		if (rows == null) {
			return List.of();
		}
		List<Expense> copy = new ArrayList<>(rows);
		copy.sort(Expense.CANONICAL);
		return List.copyOf(copy);
	}

}
