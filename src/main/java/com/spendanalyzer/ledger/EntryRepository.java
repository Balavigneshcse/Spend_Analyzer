package com.spendanalyzer.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.EntryType;
import com.spendanalyzer.model.PaymentMode;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code entries} table. Every statement carries the user id, so a row of another user is
 * simply "not found". Values only ever reach SQL as bind parameters.
 */
@Repository
public class EntryRepository {

	private static final String COLUMNS = """
			id, user_id, client_id, type, amount, account, payment_mode, description, category, item,
			ai_category, ai_confidence, ai_source, needs_review, user_corrected, entry_date, created_at, updated_at
			""";

	/** Newest first; the id makes the order stable when two rows share a timestamp. */
	private static final String NEWEST_FIRST = " ORDER BY entry_date DESC, created_at DESC, id";

	private final JdbcClient jdbc;

	public EntryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts the row unless this user already has an entry with the same client id.
	 * ON CONFLICT DO NOTHING (instead of catching a unique violation) keeps the surrounding
	 * transaction usable when the row already exists.
	 * @return false when nothing was inserted
	 */
	public boolean insertIfAbsent(EntryRow row) {
		JdbcClient.StatementSpec spec = jdbc.sql("INSERT INTO entries (" + COLUMNS + ") VALUES ("
				+ ":id, :uid, :clientId, :type, :amount, :account, :mode, :description, :category, :item, "
				+ ":aiCategory, :aiConfidence, :aiSource, :needsReview, :userCorrected, :date, :createdAt, :updatedAt) "
				+ "ON CONFLICT (user_id, client_id) DO NOTHING");
		return bind(spec, row).param("clientId", row.clientId()).param("createdAt", timestamp(row.createdAt())).update() == 1;
	}

	public Optional<EntryRow> find(UUID userId, UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM entries WHERE user_id = :uid AND id = :id")
			.param("uid", userId)
			.param("id", id)
			.query(EntryRepository::map)
			.optional();
	}

	public Optional<EntryRow> findByClientId(UUID userId, UUID clientId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM entries WHERE user_id = :uid AND client_id = :clientId")
			.param("uid", userId)
			.param("clientId", clientId)
			.query(EntryRepository::map)
			.optional();
	}

	/**
	 * Reads the entry and keeps it locked until the transaction ends. A second request that wants
	 * to edit or delete the same entry waits here; if the first one deleted it, the second finds nothing.
	 */
	public Optional<EntryRow> lock(UUID userId, UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM entries WHERE user_id = :uid AND id = :id FOR UPDATE")
			.param("uid", userId)
			.param("id", id)
			.query(EntryRepository::map)
			.optional();
	}

	/** Writes the editable columns of an entry. The id, owner, client id, type and creation time never change. */
	public void update(EntryRow row) {
		JdbcClient.StatementSpec spec = jdbc.sql("""
				UPDATE entries SET amount = :amount, account = :account, payment_mode = :mode,
					description = :description, category = :category, item = :item, ai_category = :aiCategory,
					ai_confidence = :aiConfidence, ai_source = :aiSource, needs_review = :needsReview,
					user_corrected = :userCorrected, entry_date = :date, updated_at = :updatedAt
				WHERE user_id = :uid AND id = :id AND type = :type
				""");
		if (bind(spec, row).update() != 1) {
			throw new IllegalStateException("Entry row disappeared while it was locked");
		}
	}

	public boolean delete(UUID userId, UUID id) {
		return jdbc.sql("DELETE FROM entries WHERE user_id = :uid AND id = :id")
			.param("uid", userId)
			.param("id", id)
			.update() == 1;
	}

	public List<EntryRow> search(UUID userId, EntryFilter filter, int limit, long offset) {
		Map<String, Object> params = new LinkedHashMap<>();
		String where = where(userId, filter, params);
		params.put("limit", limit);
		params.put("offset", offset);
		return jdbc.sql("SELECT " + COLUMNS + " FROM entries" + where + NEWEST_FIRST + " LIMIT :limit OFFSET :offset")
			.params(params)
			.query(EntryRepository::map)
			.list();
	}

	public long count(UUID userId, EntryFilter filter) {
		Map<String, Object> params = new LinkedHashMap<>();
		String where = where(userId, filter, params);
		return jdbc.sql("SELECT count(*) FROM entries" + where).params(params).query(Long.class).single();
	}

	/**
	 * Builds the WHERE clause from fixed fragments. Only the fragments are concatenated; every
	 * value is a named bind parameter.
	 */
	private static String where(UUID userId, EntryFilter filter, Map<String, Object> params) {
		StringBuilder sql = new StringBuilder(" WHERE user_id = :uid");
		params.put("uid", userId);
		if (filter.from() != null) {
			sql.append(" AND entry_date >= :from");
			params.put("from", filter.from());
		}
		if (filter.to() != null) {
			sql.append(" AND entry_date <= :to");
			params.put("to", filter.to());
		}
		if (filter.type() != null) {
			sql.append(" AND type = :type");
			params.put("type", filter.type().name());
		}
		if (filter.mode() != null) {
			sql.append(" AND payment_mode = :mode");
			params.put("mode", filter.mode().name());
		}
		if (filter.category() != null) {
			sql.append(" AND category = :category");
			params.put("category", filter.category().name());
		}
		if (filter.needsReviewOnly()) {
			sql.append(" AND needs_review");
		}
		if (filter.text() != null) {
			sql.append(" AND description ILIKE :text ESCAPE '!'");
			params.put("text", "%" + escapeLike(filter.text()) + "%");
		}
		return sql.toString();
	}

	/** Makes %, _ and the escape character itself match literally, so "100%" finds "100%" and not everything. */
	static String escapeLike(String text) {
		return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	/** Binds everything except client id and creation time (those are written once, on insert). */
	private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, EntryRow row) {
		return spec.param("id", row.id())
			.param("uid", row.userId())
			.param("type", row.type().name())
			.param("amount", row.amount())
			.param("account", row.account().name())
			// Explicit SQL types for the nullable columns: the driver cannot guess the type of a bare NULL.
			.param("mode", name(row.paymentMode()), Types.VARCHAR)
			.param("description", row.description(), Types.VARCHAR)
			.param("category", name(row.category()), Types.VARCHAR)
			.param("item", row.item(), Types.VARCHAR)
			.param("aiCategory", name(row.aiCategory()), Types.VARCHAR)
			.param("aiConfidence", row.aiConfidence(), Types.REAL)
			.param("aiSource", row.aiSource(), Types.VARCHAR)
			.param("needsReview", row.needsReview())
			.param("userCorrected", row.userCorrected())
			.param("date", row.date())
			.param("updatedAt", timestamp(row.updatedAt()));
	}

	private static EntryRow map(ResultSet rs, int rowNum) throws SQLException {
		float confidence = rs.getFloat("ai_confidence");
		Float aiConfidence = rs.wasNull() ? null : confidence;
		return new EntryRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getObject("client_id", UUID.class), EntryType.valueOf(rs.getString("type")),
				rs.getBigDecimal("amount"), AccountType.valueOf(rs.getString("account")),
				enumOrNull(PaymentMode.class, rs.getString("payment_mode")), rs.getString("description"),
				enumOrNull(Category.class, rs.getString("category")), rs.getString("item"),
				enumOrNull(Category.class, rs.getString("ai_category")), aiConfidence, rs.getString("ai_source"),
				rs.getBoolean("needs_review"), rs.getBoolean("user_corrected"),
				rs.getObject("entry_date", LocalDate.class), instant(rs, "created_at"), instant(rs, "updated_at"));
	}

	private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
		return value == null ? null : Enum.valueOf(type, value);
	}

	private static String name(Enum<?> value) {
		return value == null ? null : value.name();
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, OffsetDateTime.class).toInstant();
	}

	private static OffsetDateTime timestamp(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

}
