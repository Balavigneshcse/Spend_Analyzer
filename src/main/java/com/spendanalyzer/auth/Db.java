package com.spendanalyzer.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Small JDBC helpers: the PostgreSQL driver wants OffsetDateTime for TIMESTAMPTZ, the code uses Instant. */
final class Db {

	private Db() {
	}

	/** An Instant as a TIMESTAMPTZ parameter. */
	static OffsetDateTime ts(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	/** Reads a TIMESTAMPTZ column; null stays null. */
	static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
