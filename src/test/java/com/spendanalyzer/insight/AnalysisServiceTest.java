package com.spendanalyzer.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import com.spendanalyzer.insight.engine.Analysis;
import com.spendanalyzer.insight.engine.Balances;
import com.spendanalyzer.insight.engine.Debt;
import com.spendanalyzer.insight.engine.Expense;
import com.spendanalyzer.insight.engine.InsightType;
import com.spendanalyzer.insight.engine.PeriodCode;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;
import org.junit.jupiter.api.Test;

/** The service with a fixed clock and an in-memory ledger instead of the database. */
class AnalysisServiceTest {

	private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

	private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

	/** Stands in for the database; answers like the real queries do. */
	private static final class FakeRepository extends AnalysisRepository {

		final List<Expense> rows = new ArrayList<>();

		final List<Debt> debts = new ArrayList<>();

		LocalDate askedFrom;

		FakeRepository() {
			super(null);
		}

		void add(String date, String amount) {
			rows.add(new Expense(LocalDate.parse(date), new BigDecimal(amount), Category.FOOD_MEALS, PaymentMode.UPI, "meals", "meals", false));
		}

		@Override
		LocalDate firstExpenseDate(UUID userId, LocalDate today) {
			return rows.stream().map(Expense::date).filter(d -> !d.isAfter(today)).min(LocalDate::compareTo).orElse(null);
		}

		@Override
		List<Expense> expenses(UUID userId, LocalDate from, LocalDate to, int limit) {
			askedFrom = from;
			return rows.stream()
				.filter(row -> !row.date().isBefore(from) && !row.date().isAfter(to))
				.sorted(Comparator.comparing(Expense::date).reversed())
				.limit(limit)
				.toList();
		}

		@Override
		Balances balances(UUID userId) {
			return new Balances(new BigDecimal("1000"), null, null); // no cash or card row yet
		}

		@Override
		List<Debt> debts(UUID userId) {
			return debts;
		}

	}

	private static AnalysisService service(FakeRepository repository, String instant, int maxRows) {
		return new AnalysisService(repository, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), INDIA, maxRows);
	}

	@Test
	void thisMonthIsDecemberWhenItIsAlreadyDecemberInIndia() {
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("UTC")); // the server is in UTC, where it is still 30 Nov
			FakeRepository repository = new FakeRepository();
			repository.add("2026-11-30", "100");
			repository.add("2026-12-01", "40");
			Analysis analysis = service(repository, "2026-11-30T19:00:00Z", 20000).analyse(USER, PeriodCode.THIS_MONTH);

			assertThat(analysis.period().from()).isEqualTo("2026-12-01");
			assertThat(analysis.period().to()).isEqualTo("2026-12-01");
			assertThat(analysis.period().days()).isEqualTo(1);
			assertThat(analysis.totals().spent()).isEqualTo("40.00");
			assertThat(analysis.generatedAt()).isEqualTo("2026-11-30T19:00:00Z");

			Analysis lastMonth = service(repository, "2026-11-30T19:00:00Z", 20000).analyse(USER, PeriodCode.LAST_MONTH);
			assertThat(lastMonth.period().from()).isEqualTo("2026-11-01");
			assertThat(lastMonth.totals().spent()).isEqualTo("100.00");
		}
		finally {
			TimeZone.setDefault(original);
		}
	}

	@Test
	void loadsTheHistoryForTheTodayBlocksEvenForAShortPeriod() {
		FakeRepository repository = new FakeRepository();
		for (LocalDate day = LocalDate.parse("2026-10-01"); !day.isAfter(LocalDate.parse("2026-11-10")); day = day.plusDays(1)) {
			repository.add(day.toString(), "100");
		}
		Analysis analysis = service(repository, "2026-11-10T06:00:00Z", 20000).analyse(USER, PeriodCode.THIS_WEEK);

		assertThat(repository.askedFrom).isEqualTo(LocalDate.parse("2026-07-14")); // 120 days including today
		assertThat(analysis.totals().spent()).isEqualTo("200.00"); // Monday and Tuesday
		assertThat(analysis.runway().available()).isEqualTo("1000.00"); // missing accounts count as zero
		assertThat(analysis.runway().daysLeft()).isEqualTo(10);
	}

	@Test
	void theDebtsOfTheRepositoryReachTheEngine() {
		FakeRepository repository = new FakeRepository();
		for (LocalDate day = LocalDate.parse("2026-11-01"); !day.isAfter(LocalDate.parse("2026-11-10")); day = day.plusDays(1)) {
			repository.add(day.toString(), "100");
		}
		AnalysisService service = service(repository, "2026-11-10T06:00:00Z", 20000);
		assertThat(service.analyse(USER, PeriodCode.THIS_MONTH).insights())
			.noneMatch(insight -> insight.type() == InsightType.BORROWED);

		repository.debts.add(new Debt("Ravi", new BigDecimal("400.00")));
		Analysis analysis = service.analyse(USER, PeriodCode.THIS_MONTH);
		assertThat(analysis.insights()).filteredOn(insight -> insight.type() == InsightType.BORROWED)
			.singleElement()
			.satisfies(insight -> assertThat(insight.text())
				.isEqualTo("You owe Ravi ₹400. After paying it back you would have ₹600 in bank and cash."));
		// owing money is not spending
		assertThat(analysis.totals().spent()).isEqualTo("1000.00");
		assertThat(analysis.runway().available()).isEqualTo("1000.00");
	}

	@Test
	void futureDatedEntriesAreNotCounted() {
		FakeRepository repository = new FakeRepository();
		repository.add("2026-11-10", "100");
		repository.add("2026-11-11", "999"); // entries may be dated one day ahead
		Analysis analysis = service(repository, "2026-11-10T06:00:00Z", 20000).analyse(USER, PeriodCode.ALL_TIME);
		assertThat(analysis.totals().spent()).isEqualTo("100.00");
		assertThat(analysis.period().to()).isEqualTo("2026-11-10");
	}

	@Test
	void aVeryLongLedgerIsCutAtAWholeDay() {
		FakeRepository repository = new FakeRepository();
		for (LocalDate day = LocalDate.parse("2026-01-01"); !day.isAfter(LocalDate.parse("2026-11-10")); day = day.plusDays(1)) {
			repository.add(day.toString(), "100");
			repository.add(day.toString(), "50");
		}
		// room for 21 rows: the query hits the limit, so the oldest loaded day may be incomplete and is dropped
		Analysis analysis = service(repository, "2026-11-10T06:00:00Z", 21).analyse(USER, PeriodCode.ALL_TIME);
		assertThat(analysis.period().from()).isEqualTo("2026-11-01");
		assertThat(analysis.period().days()).isEqualTo(10);
		assertThat(analysis.totals().count()).isEqualTo(20);
		assertThat(analysis.totals().spent()).isEqualTo("1500.00");
	}

}
