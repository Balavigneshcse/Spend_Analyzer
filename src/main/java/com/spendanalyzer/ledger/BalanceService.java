package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.spendanalyzer.common.ApiException;
import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.EntryType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reading the balances and the one-time entry of the starting balances. */
@Service
public class BalanceService {

	static final String OPENING_DESCRIPTION = "Opening balance";

	private final AccountRepository accounts;

	private final EntryRepository entries;

	private final BalanceReader reader;

	private final Clock clock;

	public BalanceService(AccountRepository accounts, EntryRepository entries, BalanceReader reader, Clock clock) {
		this.accounts = accounts;
		this.entries = entries;
		this.reader = reader;
		this.clock = clock;
	}

	/** Current balances. Also creates the three account rows the first time a user is seen. */
	@Transactional
	public BalancesView current(UUID userId) {
		if (accounts.balances(userId).size() < AccountType.values().length) {
			accounts.ensureAccounts(userId);
		}
		return reader.read(userId);
	}

	/**
	 * Sets the starting balances, once. Each balance that changes gets an ADJUSTMENT entry for the
	 * difference, so the ledger explains the balances from the very first day.
	 * @param today the date the opening entries carry
	 */
	@Transactional
	public BalancesView setOpening(UUID userId, OpeningBalances opening, LocalDate today) {
		// The flag is the gate. Of two simultaneous submits only one flips it; the other one waits
		// for the first to finish, matches nothing and is refused, so nothing is counted twice.
		if (!accounts.markOnboarded(userId)) {
			if (accounts.onboarded(userId).isEmpty()) {
				throw ApiException.notFound("This account no longer exists.");
			}
			throw ApiException.conflict("already_onboarded",
					"Opening balances were already entered. Use a balance correction instead.");
		}
		Map<AccountType, BigDecimal> current = accounts.lock(userId, List.of(AccountType.values()));
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		for (AccountType account : AccountType.values()) {
			// "Set to", not "add": spends logged before onboarding do not shift what the user states now.
			BigDecimal difference = opening.of(account).subtract(current.get(account));
			if (difference.signum() == 0) {
				continue;
			}
			EntryRow row = new EntryRow(UUID.randomUUID(), userId, UUID.randomUUID(), EntryType.ADJUSTMENT, difference,
					account, null, OPENING_DESCRIPTION, null, null, null, null, null, false, false, today, now, now);
			entries.insertIfAbsent(row);
			accounts.apply(userId, row.effects());
		}
		return reader.read(userId);
	}

}
