package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;

/** Test fixture: a hand-made ledger with a fixed "today", turned into engine input the way the service does. */
final class Ledger {

	static final Instant GENERATED_AT = Instant.parse("2026-11-10T08:15:30Z");

	private final LocalDate today;

	private final List<Expense> rows = new ArrayList<>();

	private Balances balances = Balances.NONE;

	private final List<Debt> debts = new ArrayList<>();

	private long seed = 42;

	private Ledger(LocalDate today) {
		this.today = today;
	}

	static Ledger on(String today) {
		return new Ledger(LocalDate.parse(today));
	}

	LocalDate today() {
		return today;
	}

	List<Expense> rows() {
		return rows;
	}

	Ledger spend(String date, String amount, Category category, String item) {
		return spend(LocalDate.parse(date), amount, category, item);
	}

	Ledger spend(LocalDate date, String amount, Category category, String item) {
		return spend(date, amount, category, PaymentMode.UPI, item, false);
	}

	Ledger spend(LocalDate date, String amount, Category category, PaymentMode mode, String item, boolean needsReview) {
		rows.add(new Expense(date, new BigDecimal(amount), category, mode, item, item, needsReview));
		return this;
	}

	/** One spend on every day from {@code from} to {@code to}, both included. */
	Ledger everyDay(String from, String to, String amount, Category category, String item) {
		for (LocalDate day = LocalDate.parse(from); !day.isAfter(LocalDate.parse(to)); day = day.plusDays(1)) {
			spend(day, amount, category, item);
		}
		return this;
	}

	Ledger balances(String bank, String cash, String creditCardDue) {
		this.balances = new Balances(new BigDecimal(bank), new BigDecimal(cash), new BigDecimal(creditCardDue));
		return this;
	}

	/** The user still owes this friend this much. */
	Ledger owes(String friend, String amount) {
		debts.add(new Debt(friend, new BigDecimal(amount)));
		return this;
	}

	Ledger seed(long seed) {
		this.seed = seed;
		return this;
	}

	/** The same ledger with every amount, balance and debt multiplied. */
	Ledger times(int factor) {
		BigDecimal by = BigDecimal.valueOf(factor);
		Ledger copy = new Ledger(today);
		copy.seed = seed;
		copy.balances = new Balances(balances.bank().multiply(by), balances.cash().multiply(by),
				balances.creditCardDue().multiply(by));
		for (Debt debt : debts) {
			copy.debts.add(new Debt(debt.name(), debt.owed().multiply(by)));
		}
		for (Expense row : rows) {
			copy.rows.add(new Expense(row.date(), row.amount().multiply(by), row.category(), row.mode(), row.item(),
					row.description(), row.needsReview()));
		}
		return copy;
	}

	/** The same ledger with the rows and the debts in another order. */
	Ledger shuffled(long shuffleSeed) {
		Ledger copy = new Ledger(today);
		copy.seed = seed;
		copy.balances = balances;
		copy.rows.addAll(rows);
		Collections.shuffle(copy.rows, new Random(shuffleSeed));
		copy.debts.addAll(debts);
		Collections.shuffle(copy.debts, new Random(shuffleSeed));
		return copy;
	}

	LedgerSnapshot snapshot(PeriodCode code) {
		LocalDate first = rows.stream().map(Expense::date).filter(d -> !d.isAfter(today)).min(LocalDate::compareTo).orElse(null);
		PeriodWindow window = PeriodWindow.resolve(code, today, first);
		return LedgerSnapshot.of(window, today, rows, first, balances, debts, seed, GENERATED_AT);
	}

	InsightEngine.Report report(PeriodCode code) {
		return new InsightEngine().report(snapshot(code));
	}

	Analysis analyse(PeriodCode code) {
		return report(code).analysis();
	}

}
