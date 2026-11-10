package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.spendanalyzer.common.ApiException;
import com.spendanalyzer.model.AccountType;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The three balance rows of a user. Balances are only ever changed relatively
 * ({@code balance = balance + delta}) so overlapping requests cannot lose an update.
 * Every statement is scoped by the user id.
 */
@Repository
public class AccountRepository {

	private final JdbcClient jdbc;

	private final Clock clock;

	public AccountRepository(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** Creates the missing BANK / CASH / CREDIT_CARD rows. Safe to call any number of times, also at once. */
	public void ensureAccounts(UUID userId) {
		jdbc.sql("""
				INSERT INTO accounts (id, user_id, type, balance, updated_at)
				SELECT gen_random_uuid(), :uid, t.type, 0, :now
				FROM (VALUES ('BANK'), ('CASH'), ('CREDIT_CARD')) AS t (type)
				ON CONFLICT (user_id, type) DO NOTHING
				""").param("uid", userId).param("now", now()).update();
	}

	/** Current balances; an account that does not exist yet is simply absent from the map. */
	public Map<AccountType, BigDecimal> balances(UUID userId) {
		Map<AccountType, BigDecimal> result = new EnumMap<>(AccountType.class);
		jdbc.sql("SELECT type, balance FROM accounts WHERE user_id = :uid")
			.param("uid", userId)
			.query((rs, n) -> Map.entry(AccountType.valueOf(rs.getString("type")), rs.getBigDecimal("balance")))
			.list()
			.forEach(e -> result.put(e.getKey(), e.getValue()));
		return result;
	}

	/**
	 * Locks the given balance rows until the transaction ends and returns their current values.
	 * Rows are always locked in the order BANK, CASH, CREDIT_CARD.
	 */
	public Map<AccountType, BigDecimal> lock(UUID userId, List<AccountType> types) {
		Map<AccountType, BigDecimal> result = new EnumMap<>(AccountType.class);
		types.stream().distinct().sorted(Comparator.naturalOrder()).forEach(type -> {
			Optional<BigDecimal> balance = selectForUpdate(userId, type);
			if (balance.isEmpty()) {
				ensureAccounts(userId);
				balance = selectForUpdate(userId, type);
			}
			result.put(type, balance.orElseThrow(() -> new IllegalStateException("Account row is missing")));
		});
		return result;
	}

	/** Adds the deltas to the balances, one atomic UPDATE per account, in the fixed account order. */
	public void apply(UUID userId, List<Effect> effects) {
		for (Effect effect : LedgerEffects.net(effects)) {
			if (add(userId, effect) == 0) {
				ensureAccounts(userId);
				if (add(userId, effect) == 0) {
					throw new IllegalStateException("Account row is missing");
				}
			}
		}
	}

	/** Empty when the user does not exist (any more). */
	public Optional<Boolean> onboarded(UUID userId) {
		return jdbc.sql("SELECT onboarded FROM users WHERE id = :uid").param("uid", userId).query(Boolean.class).optional();
	}

	/**
	 * Flips the flag from false to true and says whether this call did it. Of two simultaneous
	 * calls the second waits for the first and then matches no row.
	 */
	public boolean markOnboarded(UUID userId) {
		return jdbc.sql("UPDATE users SET onboarded = TRUE WHERE id = :uid AND onboarded = FALSE")
			.param("uid", userId)
			.update() == 1;
	}

	private Optional<BigDecimal> selectForUpdate(UUID userId, AccountType type) {
		return jdbc.sql("SELECT balance FROM accounts WHERE user_id = :uid AND type = :type FOR UPDATE")
			.param("uid", userId)
			.param("type", type.name())
			.query(BigDecimal.class)
			.optional();
	}

	private int add(UUID userId, Effect effect) {
		try {
			return jdbc
				.sql("UPDATE accounts SET balance = balance + :delta, updated_at = :now WHERE user_id = :uid AND type = :type")
				.param("delta", effect.delta())
				.param("now", now())
				.param("uid", userId)
				.param("type", effect.account().name())
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			// NUMERIC(12,2) overflow: only reachable after thousands of maximum-size entries.
			throw ApiException.badRequest("validation", "That would make the balance larger than this app can hold.");
		}
	}

	private OffsetDateTime now() {
		return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

}
