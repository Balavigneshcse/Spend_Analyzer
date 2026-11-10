package com.spendanalyzer.auth;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The mail_log table: one row for every "send me a code" request and one for every notice mail.
 * The limits are counted from here, so they survive restarts.
 * <p>
 * A request that sent nothing (unknown address, existing account, breaker open) is still written,
 * with kind SUPPRESSED and the client address: the per-IP limit must not show which addresses exist.
 * Notice mails carry no address and so do not count against a client.
 */
@Repository
class MailLog {

	/** A code request for which no mail went out. */
	static final String SUPPRESSED = "SUPPRESSED";

	private final JdbcClient jdbc;

	MailLog(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void record(String recipient, String kind, String ip, Instant now) {
		jdbc.sql("INSERT INTO mail_log (recipient, kind, ip, created_at) VALUES (?, ?, ?, ?)")
			.params(recipient, kind, ip, Db.ts(now))
			.update();
	}

	/** Mails really sent since the given moment, to anybody. */
	int mailsSince(Instant since) {
		return jdbc.sql("SELECT count(*) FROM mail_log WHERE created_at > ? AND kind <> ?")
			.params(Db.ts(since), SUPPRESSED)
			.query(Integer.class)
			.single();
	}

	boolean sentSince(String recipient, String kind, Instant since) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM mail_log WHERE recipient = ? AND kind = ? AND created_at > ?)")
			.params(recipient, kind, Db.ts(since))
			.query(Boolean.class)
			.single();
	}

	/** The times of the newest code requests of one client address, newest first, at most {@code limit}. */
	List<Instant> requestsFrom(String ip, Instant since, int limit) {
		return jdbc.sql("SELECT created_at FROM mail_log WHERE ip = ? AND created_at > ? ORDER BY created_at DESC LIMIT ?")
			.params(ip, Db.ts(since), limit)
			.query((rs, n) -> Db.instant(rs, "created_at"))
			.list();
	}

}
