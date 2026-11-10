package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What a user owes friends. There is no table of debts: a debt is the sum of the user's BORROW
 * entries minus the REPAY entries of the same {@code person_key}, so it can never disagree with
 * the ledger. Every statement is scoped by the user id.
 */
@Repository
public class DebtRepository {

	/** One row per friend. The shown name is the spelling of the friend's oldest entry. */
	private static final String PER_FRIEND = """
			SELECT (array_agg(person ORDER BY created_at, id))[1] AS name,
				COALESCE(SUM(amount) FILTER (WHERE type = 'BORROW'), 0) AS borrowed,
				COALESCE(SUM(amount) FILTER (WHERE type = 'REPAY'), 0) AS repaid,
				MAX(entry_date) AS last_date,
				MAX(created_at) AS last_used
			FROM entries
			WHERE user_id = :uid AND person_key IS NOT NULL
			""";

	private final JdbcClient jdbc;

	public DebtRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Lines up everything of one user that changes a debt (borrow, pay back, deleting either) until
	 * the transaction ends. "Is this pay-back more than is owed?" is a read followed by a write, and
	 * two such requests at once would each see the old sum; with this lock the second one waits and
	 * then reads the first one's result.
	 * <p>
	 * It is an advisory lock on a number made from the user id, not a lock on the users row: every
	 * insert of an entry already takes a share lock on that row for its foreign key, so a row lock
	 * here would make ordinary spends wait and could deadlock with one that holds a balance row.
	 * An advisory lock touches no row at all. It must be the first lock a transaction takes (before
	 * the entry row and the balance rows); nothing else ever waits for it while holding those.
	 * Two users whose numbers collide would only wait for each other, never see each other's data.
	 * <p>
	 * Relies on the default READ COMMITTED isolation: the statements after the wait see what the
	 * transaction before committed.
	 */
	public void lock(UUID userId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "debts:" + userId)
			.query((rs, row) -> Boolean.TRUE)
			.single();
	}

	/** The friend with this key, or empty when the user has no entry with them. */
	public Optional<FriendDebt> find(UUID userId, String personKey) {
		return jdbc.sql(PER_FRIEND + " AND person_key = :key GROUP BY person_key")
			.param("uid", userId)
			.param("key", personKey)
			.query(DebtRepository::map)
			.optional();
	}

	/** Every friend the user has an entry with, also the ones who are paid back in full. In no particular order. */
	public List<FriendDebt> all(UUID userId) {
		return jdbc.sql(PER_FRIEND + " GROUP BY person_key").param("uid", userId).query(DebtRepository::map).list();
	}

	/** Everything borrowed and not yet paid back, over all friends. */
	public BigDecimal totalOwed(UUID userId) {
		return jdbc.sql("""
				SELECT COALESCE(SUM(CASE WHEN type = 'BORROW' THEN amount ELSE -amount END), 0)
				FROM entries
				WHERE user_id = :uid AND person_key IS NOT NULL
				""").param("uid", userId).query(BigDecimal.class).single();
	}

	private static FriendDebt map(ResultSet rs, int rowNum) throws SQLException {
		return new FriendDebt(rs.getString("name"), rs.getBigDecimal("borrowed"), rs.getBigDecimal("repaid"),
				rs.getObject("last_date", LocalDate.class), rs.getObject("last_used", OffsetDateTime.class).toInstant());
	}

}
