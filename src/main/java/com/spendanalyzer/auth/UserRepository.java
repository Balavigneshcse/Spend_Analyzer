package com.spendanalyzer.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The users table. Emails are always passed in their normalised (lower-case) form. */
@Repository
public class UserRepository {

	private static final String COLUMNS = "id, email, name, phone, password_hash";

	private static final RowMapper<UserRow> ROW = (rs, n) -> new UserRow(rs.getObject("id", UUID.class),
			rs.getString("email"), rs.getString("name"), rs.getString("phone"), rs.getString("password_hash"));

	private final JdbcClient jdbc;

	public UserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<UserRow> findByEmail(String email) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE email = ?").param(email).query(ROW).optional();
	}

	public Optional<UserRow> findById(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = ?").param(id).query(ROW).optional();
	}

	public boolean existsByEmail(String email) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE email = ?)").param(email).query(Boolean.class).single();
	}

	/** @return false when the email already has an account (nothing is inserted then) */
	public boolean insert(UUID id, String email, String name, String phone, String passwordHash, Instant now) {
		return jdbc.sql("""
				INSERT INTO users (id, email, name, phone, password_hash, created_at)
				VALUES (?, ?, ?, ?, ?, ?)
				ON CONFLICT (email) DO NOTHING""").params(id, email, name, phone, passwordHash, Db.ts(now)).update() == 1;
	}

	public void updatePassword(UUID id, String passwordHash) {
		jdbc.sql("UPDATE users SET password_hash = ? WHERE id = ?").params(passwordHash, id).update();
	}

	/** Deletes the user; every table with the user's data cascades from here. */
	public boolean delete(UUID id) {
		return jdbc.sql("DELETE FROM users WHERE id = ?").param(id).update() == 1;
	}

}
