package com.spendanalyzer.ledger;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.spendanalyzer.common.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Collects everything the app holds about one user for the downloads (JSON backup and Excel file). */
@Service
public class ExportService {

	/** Upper bound of the download; the newest entries win. Keeps one request from filling the small server's memory. */
	static final int MAX_ENTRIES = 20_000;

	public record UserView(String name, String email, String phoneMasked) {
	}

	public record Export(String exportedAt, UserView user, BalancesView balances, DebtsView debts,
			List<EntryView> entries) {
	}

	/**
	 * Everything of one user, read in one snapshot, before it is turned into a file.
	 *
	 * @param entries newest first, at most {@link #MAX_ENTRIES}
	 * @param cut true when the user has more entries than {@link #MAX_ENTRIES} and the oldest were left out
	 */
	public record Snapshot(Instant exportedAt, UserView user, BalancesView balances, DebtsView debts,
			List<EntryRow> entries, boolean cut) {
	}

	private final JdbcClient jdbc;

	private final EntryRepository entries;

	private final BalanceReader balances;

	private final DebtReader debts;

	private final Clock clock;

	public ExportService(JdbcClient jdbc, EntryRepository entries, BalanceReader balances, DebtReader debts,
			Clock clock) {
		this.jdbc = jdbc;
		this.entries = entries;
		this.balances = balances;
		this.debts = debts;
		this.clock = clock;
	}

	/** The JSON backup. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Export export(UUID userId) {
		Snapshot data = snapshot(userId);
		return new Export(data.exportedAt().toString(), data.user(), data.balances(), data.debts(),
				data.entries().stream().map(EntryView::of).toList());
	}

	/** One snapshot for all the reads, so the balances and debts in the file match the entries in the file. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Snapshot snapshot(UUID userId) {
		UserView user = jdbc.sql("SELECT name, email, phone FROM users WHERE id = :uid")
			.param("uid", userId)
			.query((rs, n) -> new UserView(rs.getString("name"), rs.getString("email"), maskPhone(rs.getString("phone"))))
			.optional()
			.orElseThrow(() -> ApiException.notFound("This account no longer exists."));
		// one row more than the cap tells whether the cap cut the list, without a second query
		List<EntryRow> rows = entries.search(userId, EntryFilter.none(), MAX_ENTRIES + 1, 0);
		boolean cut = rows.size() > MAX_ENTRIES;
		return new Snapshot(clock.instant().truncatedTo(ChronoUnit.SECONDS), user, balances.read(userId),
				debts.read(userId), cut ? rows.subList(0, MAX_ENTRIES) : rows, cut);
	}

	/** "+919876543210" becomes "+91 ••••••3210": enough to recognise the number, not enough to use it. */
	static String maskPhone(String phone) {
		if (phone == null || phone.length() < 4) {
			return "";
		}
		return "+91 ••••••" + phone.substring(phone.length() - 4);
	}

}
