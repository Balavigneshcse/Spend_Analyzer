package com.spendanalyzer.ledger;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.spendanalyzer.common.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Collects everything the app holds about one user for the JSON download. */
@Service
public class ExportService {

	/** Upper bound of the download; the newest entries win. Keeps one request from filling the small server's memory. */
	static final int MAX_ENTRIES = 20_000;

	public record UserView(String name, String email, String phoneMasked) {
	}

	public record Export(String exportedAt, UserView user, BalancesView balances, List<EntryView> entries) {
	}

	private final JdbcClient jdbc;

	private final EntryRepository entries;

	private final BalanceReader balances;

	private final Clock clock;

	public ExportService(JdbcClient jdbc, EntryRepository entries, BalanceReader balances, Clock clock) {
		this.jdbc = jdbc;
		this.entries = entries;
		this.balances = balances;
		this.clock = clock;
	}

	/** One snapshot for all three reads, so the balances in the file match the entries in the file. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Export export(UUID userId) {
		UserView user = jdbc.sql("SELECT name, email, phone FROM users WHERE id = :uid")
			.param("uid", userId)
			.query((rs, n) -> new UserView(rs.getString("name"), rs.getString("email"), maskPhone(rs.getString("phone"))))
			.optional()
			.orElseThrow(() -> ApiException.notFound("This account no longer exists."));
		List<EntryView> items = entries.search(userId, EntryFilter.none(), MAX_ENTRIES, 0)
			.stream()
			.map(EntryView::of)
			.toList();
		return new Export(clock.instant().truncatedTo(ChronoUnit.SECONDS).toString(), user, balances.read(userId), items);
	}

	/** "+919876543210" becomes "+91 ••••••3210": enough to recognise the number, not enough to use it. */
	static String maskPhone(String phone) {
		if (phone == null || phone.length() < 4) {
			return "";
		}
		return "+91 ••••••" + phone.substring(phone.length() - 4);
	}

}
