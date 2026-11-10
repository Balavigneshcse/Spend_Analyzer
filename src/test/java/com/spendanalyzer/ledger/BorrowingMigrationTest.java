package com.spendanalyzer.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.spendanalyzer.testsupport.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V2 on a database that already lives on V1 and holds data, the way the deployed one does. The
 * shared test database is migrated to the newest version before any test runs, so this test builds
 * its own V1 database in a throw-away schema, fills it, and only then lets V2 run.
 */
@IntegrationTest
class BorrowingMigrationTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 1);

	@Value("${spring.datasource.url}")
	String url;

	@Value("${spring.datasource.username}")
	String username;

	@Value("${spring.datasource.password}")
	String password;

	String schema;

	/** Own connections (not the application's pool) that see only the throw-away schema. */
	DriverManagerDataSource dataSource;

	JdbcClient db;

	UUID user;

	@BeforeEach
	void setUp() {
		schema = "v2_migration_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		dataSource = new DriverManagerDataSource(url, username, password);
		dataSource.setSchema(schema);
		db = JdbcClient.create(dataSource);
	}

	@AfterEach
	void dropTheSchema() {
		// the name is made of hex digits above, nothing else can get into this statement
		db.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
	}

	private Flyway flyway(String target) {
		FluentConfiguration configuration = Flyway.configure()
			.dataSource(dataSource)
			.schemas(schema)
			.defaultSchema(schema)
			.locations("classpath:db/migration");
		if (target != null) {
			configuration.target(target);
		}
		return configuration.load();
	}

	/** One entry of every V1 type, for a user with the three accounts. */
	private void fillWithV1Data() {
		user = UUID.randomUUID();
		db.sql("INSERT INTO users (id, email, name, phone, password_hash, onboarded) VALUES (?, ?, ?, ?, ?, TRUE)")
			.params(user, "v1-user@example.com", "V1 User", "+919876543210", "{noop}x")
			.update();
		for (String account : List.of("BANK", "CASH", "CREDIT_CARD")) {
			db.sql("INSERT INTO accounts (id, user_id, type, balance) VALUES (?, ?, ?, 100)")
				.params(UUID.randomUUID(), user, account)
				.update();
		}
		db.sql("""
				INSERT INTO entries (id, user_id, client_id, type, amount, account, payment_mode, description, category,
				                     item, ai_category, ai_confidence, ai_source, entry_date)
				VALUES (?, ?, ?, 'EXPENSE', 40, 'BANK', 'UPI', 'juse', 'SNACKS_BEVERAGES', 'juice', 'SNACKS_BEVERAGES',
				        0.88, 'LEXICON_FUZZY', ?)
				""").params(UUID.randomUUID(), user, UUID.randomUUID(), DAY).update();
		insertV1(user, "INCOME", "5000", "BANK", "pocket money");
		insertV1(user, "WITHDRAWAL", "700", "BANK", null);
		insertV1(user, "CARD_PAYMENT", "900", "CASH", null);
		insertV1(user, "ADJUSTMENT", "-120.50", "CREDIT_CARD", "Opening balance");
	}

	private void insertV1(UUID owner, String type, String amount, String account, String description) {
		db.sql("""
				INSERT INTO entries (id, user_id, client_id, type, amount, account, description, entry_date)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""").params(UUID.randomUUID(), owner, UUID.randomUUID(), type, new BigDecimal(amount), account, description, DAY).update();
	}

	private void insertDebt(String type, String account, String person, String personKey) {
		db.sql("""
				INSERT INTO entries (id, user_id, client_id, type, amount, account, person, person_key, entry_date)
				VALUES (?, ?, ?, ?, 250, ?, ?, ?, ?)
				""").params(UUID.randomUUID(), user, UUID.randomUUID(), type, account, person, personKey, DAY).update();
	}

	/** Everything V1 knew about the entries, as text, in a stable order. */
	private List<Map<String, Object>> v1Columns() {
		return db.sql("""
				SELECT id, user_id, client_id, type, amount::text AS amount, account, payment_mode, description, category, item,
					ai_category, ai_confidence, ai_source, needs_review, user_corrected, entry_date::text AS entry_date,
					created_at::text AS created_at, updated_at::text AS updated_at
				FROM entries ORDER BY type
				""").query().listOfRows();
	}

	/** Names of the checks that cover the type column alone. */
	private List<String> typeChecks() {
		return db.sql("""
				SELECT c.conname FROM pg_constraint c
				WHERE c.conrelid = 'entries'::regclass AND c.contype = 'c'
					AND c.conkey = ARRAY[(SELECT a.attnum FROM pg_attribute a WHERE a.attrelid = c.conrelid AND a.attname = 'type')]
				""").query(String.class).list();
	}

	/** The migrations that ran. (Flyway also notes that it created the schema, as a row without a version.) */
	private List<String> appliedVersions() {
		return db.sql("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank")
			.query(String.class)
			.list();
	}

	@Test
	void v2AppliesOnTopOfV1DataAndLeavesItUntouched() {
		MigrateResult toV1 = flyway("1").migrate();
		assertThat(toV1.migrationsExecuted).isEqualTo(1);
		assertThat(appliedVersions()).containsExactly("1");
		fillWithV1Data();
		List<Map<String, Object>> before = v1Columns();
		assertThat(before).hasSize(5);
		// this really is the old database: it knows neither the type nor the column
		assertThatThrownBy(() -> insertV1(user, "BORROW", "250", "BANK", null)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertDebt("BORROW", "BANK", "Ravi", "ravi")).isInstanceOf(Exception.class);

		MigrateResult toLatest = flyway(null).migrate();

		assertThat(toLatest.success).isTrue();
		assertThat(toLatest.migrationsExecuted).isEqualTo(1);
		assertThat(appliedVersions()).containsExactly("1", "2");
		// every old row is still there, unchanged, and has no friend
		assertThat(v1Columns()).isEqualTo(before);
		assertThat(db.sql("SELECT count(*) FROM entries WHERE person IS NOT NULL OR person_key IS NOT NULL")
			.query(Long.class)
			.single()).isZero();
		assertThat(db.sql("SELECT balance::text FROM accounts WHERE user_id = ?").param(user).query(String.class).list())
			.containsOnly("100.00");

		// the new entries fit, next to the old kinds that still work
		insertDebt("BORROW", "BANK", "Ravi", "ravi");
		insertDebt("BORROW", "CASH", "Ravi", "ravi");
		insertDebt("REPAY", "CASH", "RAVI", "ravi");
		insertV1(user, "INCOME", "10", "CASH", null);
		assertThat(db.sql("""
				SELECT SUM(CASE WHEN type = 'BORROW' THEN amount ELSE -amount END)::text
				FROM entries WHERE user_id = ? AND person_key = 'ravi'
				""").param(user).query(String.class).single()).isEqualTo("250.00");

		// a second run finds nothing to do
		assertThat(flyway(null).migrate().migrationsExecuted).isZero();
	}

	@Test
	void afterV2TheDatabaseItselfRefusesEntriesThatBreakTheRules() {
		flyway(null).migrate();
		assertThat(appliedVersions()).containsExactly("1", "2");
		fillWithV1Data();

		// a friend exactly on BORROW and REPAY, with name and key
		assertThatThrownBy(() -> insertDebt("BORROW", "BANK", null, null)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertDebt("REPAY", "BANK", "Ravi", null)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertDebt("BORROW", "BANK", null, "ravi")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertDebt("BORROW", "BANK", "", "")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertDebt("BORROW", "BANK", "x".repeat(41), "x".repeat(41)))
			.isInstanceOf(DataIntegrityViolationException.class);
		for (String other : List.of("INCOME", "WITHDRAWAL", "CARD_PAYMENT", "ADJUSTMENT")) {
			assertThatThrownBy(() -> insertDebt(other, "BANK", "Ravi", "ravi")).isInstanceOf(DataIntegrityViolationException.class);
		}
		// never through the card due
		assertThatThrownBy(() -> insertDebt("BORROW", "CREDIT_CARD", "Ravi", "ravi"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertDebt("REPAY", "CREDIT_CARD", "Ravi", "ravi"))
			.isInstanceOf(DataIntegrityViolationException.class);
		// the list of types is still closed, and the amount rule covers the new types
		assertThatThrownBy(() -> insertV1(user, "LEND", "250", "BANK", null)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> db.sql("""
				INSERT INTO entries (id, user_id, client_id, type, amount, account, person, person_key, entry_date)
				VALUES (?, ?, ?, 'BORROW', 0, 'BANK', 'Ravi', 'ravi', ?)
				""").params(UUID.randomUUID(), user, UUID.randomUUID(), DAY).update())
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(db.sql("SELECT count(*) FROM entries WHERE type IN ('BORROW', 'REPAY')").query(Long.class).single()).isZero();

		// the index for "what do I owe each friend"
		String index = db.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = 'entries_user_person_idx'")
			.param(schema)
			.query(String.class)
			.single();
		assertThat(index).contains("(user_id, person_key)").contains("person_key IS NOT NULL");
		assertThat(typeChecks()).containsExactly("entries_type_check");
	}

	@Test
	void v2ReplacesTheTypeCheckWhateverNamePostgresGaveIt() {
		flyway("1").migrate();
		assertThat(typeChecks()).hasSize(1);
		// V1 left the name to the database; pretend this database chose another one
		db.sql("ALTER TABLE entries RENAME CONSTRAINT " + typeChecks().get(0) + " TO entries_check_from_long_ago").update();
		fillWithV1Data();

		flyway(null).migrate();

		assertThat(typeChecks()).containsExactly("entries_type_check");
		insertDebt("BORROW", "BANK", "Ravi", "ravi");
		insertDebt("REPAY", "CASH", "Ravi", "ravi");
		assertThatThrownBy(() -> insertV1(user, "LEND", "250", "BANK", null)).isInstanceOf(DataIntegrityViolationException.class);
		// the named multi-column checks of V1 were not touched
		assertThat(db.sql("SELECT conname FROM pg_constraint WHERE conrelid = 'entries'::regclass AND contype = 'c'")
			.query(String.class)
			.list()).contains("entries_amount_ck", "entries_expense_ck", "entries_person_ck", "entries_debt_account_ck");
	}

}
