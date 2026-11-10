package com.spendanalyzer.insight;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.spendanalyzer.insight.engine.Balances;
import com.spendanalyzer.insight.engine.Debt;
import com.spendanalyzer.insight.engine.Expense;
import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads what the analysis needs. Every statement is scoped to one user; only EXPENSE rows are spending. */
@Repository
class AnalysisRepository {

	private final JdbcClient jdbc;

	AnalysisRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Date of the user's first expense up to today, or null when there is none. */
	LocalDate firstExpenseDate(UUID userId, LocalDate today) {
		List<LocalDate> dates = jdbc
			.sql("SELECT min(entry_date) FROM entries WHERE user_id = ? AND type = 'EXPENSE' AND entry_date <= ?")
			.params(userId, today)
			.query((rs, row) -> rs.getObject(1, LocalDate.class))
			.list();
		return dates.isEmpty() ? null : dates.get(0);
	}

	/** Expenses dated from {@code from} to {@code to}, newest first, at most {@code limit} rows. */
	List<Expense> expenses(UUID userId, LocalDate from, LocalDate to, int limit) {
		return jdbc.sql("""
				SELECT entry_date, amount, category, payment_mode, item, description, needs_review
				FROM entries
				WHERE user_id = ? AND type = 'EXPENSE' AND entry_date BETWEEN ? AND ?
				ORDER BY entry_date DESC, created_at DESC, id
				LIMIT ?
				""").params(userId, from, to, limit).query(AnalysisRepository::expense).list();
	}

	/** Current balances; an account the user has not set up yet counts as zero. */
	Balances balances(UUID userId) {
		Map<AccountType, BigDecimal> byType = new EnumMap<>(AccountType.class);
		jdbc.sql("SELECT type, balance FROM accounts WHERE user_id = ?").param(userId).query(rs -> {
			byType.put(AccountType.valueOf(rs.getString("type")), rs.getBigDecimal("balance"));
		});
		return new Balances(byType.get(AccountType.BANK), byType.get(AccountType.CASH),
				byType.get(AccountType.CREDIT_CARD));
	}

	/**
	 * What the user still owes each friend: borrowed minus paid back, per friend, only where
	 * something is left. The name is the spelling of the friend's first entry. This is the only
	 * place BORROW and REPAY rows are read here; they are never spending.
	 */
	List<Debt> debts(UUID userId) {
		return jdbc.sql("""
				SELECT (array_agg(person ORDER BY created_at, id))[1] AS name,
					SUM(CASE WHEN type = 'BORROW' THEN amount ELSE -amount END) AS owed
				FROM entries
				WHERE user_id = ? AND type IN ('BORROW', 'REPAY')
				GROUP BY person_key
				HAVING SUM(CASE WHEN type = 'BORROW' THEN amount ELSE -amount END) > 0
				""").param(userId).query((rs, row) -> new Debt(rs.getString("name"), rs.getBigDecimal("owed"))).list();
	}

	private static Expense expense(ResultSet rs, int row) throws SQLException {
		return new Expense(rs.getObject("entry_date", LocalDate.class), rs.getBigDecimal("amount"),
				Category.valueOf(rs.getString("category")), PaymentMode.valueOf(rs.getString("payment_mode")),
				rs.getString("item"), rs.getString("description"), rs.getBoolean("needs_review"));
	}

}
