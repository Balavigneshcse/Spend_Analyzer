package com.spendanalyzer.insight.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;
import org.junit.jupiter.api.Test;

/** Rules that must hold for any ledger, checked on a busy four-month ledger and on small ones. */
class InsightEnginePropertyTest {

	/** A rupee figure as a sentence prints it: ₹560, ₹1,250, ₹1,23,456, ₹107.50. */
	private static final Pattern RUPEES = Pattern.compile("₹\\d+(,\\d+)*(\\.\\d{2})?");

	/** A student's four months: meals, juice, bus, a weekly movie, rent, a recharge and one big purchase. */
	static Ledger busyLedger() {
		Ledger ledger = Ledger.on("2026-11-28").balances("8000", "700", "1200");
		Random random = new Random(7);
		String[] mealPrices = { "60", "80", "100", "120" };
		for (LocalDate day = LocalDate.parse("2026-08-01"); !day.isAfter(ledger.today()); day = day.plusDays(1)) {
			ledger.spend(day, mealPrices[random.nextInt(mealPrices.length)], Category.FOOD_MEALS, "meals");
			if (random.nextInt(10) < 6) {
				ledger.spend(day, "40", Category.SNACKS_BEVERAGES, "juice");
			}
			if (random.nextInt(10) < 5) {
				ledger.spend(day, "20", Category.TRANSPORT_FUEL, PaymentMode.CASH, "bus ticket", false);
			}
			if (random.nextInt(10) < 1) {
				ledger.spend(day, "10", Category.EDUCATION_STATIONERY, PaymentMode.CASH, "pen", false);
			}
			if (random.nextInt(25) == 0) {
				ledger.spend(day, "35", Category.OTHER, PaymentMode.CASH, "misc", true);
			}
			if (day.getDayOfWeek() == DayOfWeek.SATURDAY) {
				ledger.spend(day, "250", Category.ENTERTAINMENT_SUBS, "movie");
			}
			if (day.getDayOfMonth() == 5) {
				ledger.spend(day, "4500", Category.RENT_UTILITIES, "rent");
			}
			if (day.getDayOfMonth() == 3) {
				ledger.spend(day, "299", Category.MOBILE_INTERNET, "recharge");
			}
		}
		ledger.spend(LocalDate.parse("2026-11-12"), "2500", Category.SHOPPING, PaymentMode.CREDIT_CARD, "shoes", false);
		return ledger;
	}

	private static List<Ledger> ledgers() {
		List<Ledger> ledgers = new ArrayList<>();
		ledgers.add(busyLedger());
		ledgers.add(busyLedger().balances("300", "50", "9000"));
		ledgers.add(busyLedger().times(10));
		ledgers.add(Ledger.on("2026-11-28")
			.everyDay("2026-11-01", "2026-11-28", "80", Category.FOOD_MEALS, "lunch")
			.everyDay("2026-11-01", "2026-11-14", "40", Category.SNACKS_BEVERAGES, "juice")
			.balances("5000", "500", "0"));
		ledgers.add(Ledger.on("2026-11-10")
			.everyDay("2026-10-12", "2026-11-10", "165.50", Category.FOOD_MEALS, "meals")
			.balances("2000", "150.75", "0"));
		ledgers.add(Ledger.on("2026-11-10").spend("2026-11-09", "99.99", Category.OTHER, "thing").balances("10", "0", "5"));
		ledgers.add(Ledger.on("2026-11-10"));
		return ledgers;
	}

	@Test
	void everyRupeeFigureInASentenceIsAComputedFact() {
		int figures = 0;
		for (Ledger ledger : ledgers()) {
			for (PeriodCode code : PeriodCode.values()) {
				InsightEngine.Report report = ledger.report(code);
				for (String text : InsightEngineLedgerTest.allText(report.analysis())) {
					Matcher matcher = RUPEES.matcher(text);
					while (matcher.find()) {
						figures++;
						assertThat(report.rupeeFacts()).as("figure in: %s", text).contains(matcher.group());
					}
				}
			}
		}
		assertThat(figures).isGreaterThan(100); // the check really looked at something
	}

	@Test
	void sentenceFiguresAgreeWithTheStructuredNumbers() {
		Analysis analysis = busyLedger().analyse(PeriodCode.THIS_MONTH);
		// the summary's total is the structured total
		assertThat(analysis.summary()).contains(Rupees.format(new java.math.BigDecimal(analysis.totals().spent())));
		// a leak sentence repeats the item's row in topItems
		for (Analysis.Insight insight : analysis.insights()) {
			if (insight.type() == InsightType.SMALL_LEAK) {
				String item = insight.id().substring("leak-".length());
				Analysis.TopItem row = analysis.topItems().stream().filter(i -> i.item().equals(item)).findFirst().orElseThrow();
				assertThat(insight.text()).contains(row.count() + " times").contains(Rupees.format(new java.math.BigDecimal(row.amount())));
			}
		}
	}

	@Test
	void limitsOnWhatIsShown() {
		for (Ledger ledger : ledgers()) {
			for (PeriodCode code : PeriodCode.values()) {
				Analysis analysis = ledger.analyse(code);
				assertThat(analysis.insights().size()).isLessThanOrEqualTo(6);
				assertThat(analysis.tips().size()).isLessThanOrEqualTo(3);
				Map<InsightType, Integer> perType = new EnumMap<>(InsightType.class);
				analysis.insights().forEach(i -> perType.merge(i.type(), 1, Integer::sum));
				assertThat(perType.values()).allMatch(n -> n <= 2);
				assertThat(analysis.insights().stream().map(Analysis.Insight::id).distinct().count())
					.isEqualTo(analysis.insights().size());
				// plain text only: no markup of any kind, no broken numbers
				for (String text : InsightEngineLedgerTest.allText(analysis)) {
					assertThat(text).isNotBlank().doesNotContain("<").doesNotContain("{").doesNotContain("**")
						.doesNotContain("NaN").doesNotContain("Infinity").doesNotContain("null");
				}
				long sentences = analysis.summary().chars().filter(c -> c == '.').count();
				assertThat(analysis.summary().split("\\. ").length).as(analysis.summary()).isBetween(1, 5);
				assertThat(sentences).isGreaterThan(0);
				assertNumbersAreSane(analysis);
			}
		}
	}

	private static void assertNumbersAreSane(Analysis analysis) {
		for (Analysis.CategoryShare share : analysis.byCategory()) {
			assertThat(share.percent()).isBetween(0.0, 100.0);
		}
		for (Analysis.ModeShare share : analysis.byMode()) {
			assertThat(share.percent()).isBetween(0.0, 100.0);
		}
		if (analysis.needsWants() != null) {
			assertThat(analysis.needsWants().wantsPercent()).isBetween(0.0, 100.0);
		}
		if (analysis.totals().changePercent() != null) {
			assertThat(analysis.totals().changePercent()).isFinite();
		}
		if (analysis.forecast() != null) {
			assertThat(new java.math.BigDecimal(analysis.forecast().monthEndLow()))
				.isLessThanOrEqualTo(new java.math.BigDecimal(analysis.forecast().monthEndExpected()));
			assertThat(new java.math.BigDecimal(analysis.forecast().monthEndExpected()))
				.isLessThanOrEqualTo(new java.math.BigDecimal(analysis.forecast().monthEndHigh()));
		}
		if (analysis.runway() != null) {
			assertThat(analysis.runway().daysLeft()).isBetween(0, 365);
		}
		assertThat(analysis.daily()).hasSize(Math.min(90, analysis.period().days()));
	}

	@Test
	void aBusyLedgerGetsAUsefulAnalysis() {
		Analysis analysis = busyLedger().analyse(PeriodCode.THIS_MONTH);
		assertThat(analysis.insights()).hasSize(6);
		// the one pair of shoes is compared with all spends, because Shopping has no history of its own
		assertThat(analysis.insights()).anyMatch(i -> i.type() == InsightType.UNUSUAL_SPEND
				&& i.text().startsWith("₹2,500 on shoes on 12 Nov stands out: about"));
		assertThat(analysis.insights()).anyMatch(i -> i.id().equals("recurring-rent"));
		// rent is a known bill: never "unusual", and worth a reminder; the weekly movie is a habit, not a bill
		assertThat(analysis.insights()).noneMatch(i -> i.type() == InsightType.UNUSUAL_SPEND && i.text().contains("rent"));
		assertThat(analysis.tips()).extracting(Analysis.Tip::text).contains("Keep ₹4,500 aside for rent around 6 Dec.")
			.noneMatch(text -> text.contains("aside for movie"));
		assertThat(analysis.insights()).anyMatch(i -> i.type() == InsightType.SMALL_LEAK && i.text().contains("juice"));
		assertThat(analysis.tips()).isNotEmpty();
		assertThat(analysis.health().components()).extracting(Analysis.Component::name)
			.containsExactly("Runway", "Needs/wants", "Trend", "Spikes", "Leaks", "Tracking");
		assertThat(analysis.health().components().stream().mapToInt(Analysis.Component::max).toArray())
			.containsExactly(30, 20, 15, 15, 10, 10);
		assertThat(analysis.health().provisional()).isFalse();
		assertThat(analysis.runway()).isNotNull();
		assertThat(analysis.forecast()).isNotNull();
		assertThat(analysis.dataQuality().enoughData()).isTrue();
	}

	@Test
	void multiplyingEveryAmountByTenKeepsSharesAndHealth() {
		for (PeriodCode code : PeriodCode.values()) {
			Analysis base = busyLedger().analyse(code);
			Analysis scaled = busyLedger().times(10).analyse(code);

			assertThat(scaled.totals().count()).isEqualTo(base.totals().count());
			assertThat(scaled.totals().changePercent()).isEqualTo(base.totals().changePercent());
			assertThat(scaled.byCategory()).extracting(Analysis.CategoryShare::category, Analysis.CategoryShare::percent)
				.containsExactlyElementsOf(base.byCategory()
					.stream()
					.map(s -> org.assertj.core.api.Assertions.tuple(s.category(), s.percent()))
					.toList());
			assertThat(scaled.byMode()).extracting(Analysis.ModeShare::mode, Analysis.ModeShare::percent)
				.containsExactlyElementsOf(base.byMode()
					.stream()
					.map(s -> org.assertj.core.api.Assertions.tuple(s.mode(), s.percent()))
					.toList());
			assertThat(scaled.needsWants().wantsPercent()).isEqualTo(base.needsWants().wantsPercent());
			assertThat(scaled.runway().daysLeft()).isCloseTo(base.runway().daysLeft(), within(1));
			assertThat(scaled.health().score()).isCloseTo(base.health().score(), within(1));
			assertThat(scaled.health().band()).isEqualTo(base.health().band());
			assertThat(scaled.health().components()).extracting(Analysis.Component::name, Analysis.Component::max)
				.containsExactlyElementsOf(base.health()
					.components()
					.stream()
					.map(c -> org.assertj.core.api.Assertions.tuple(c.name(), c.max()))
					.toList());
		}
	}

	@Test
	void theOrderOfTheRowsDoesNotMatter() {
		for (PeriodCode code : PeriodCode.values()) {
			Analysis base = busyLedger().analyse(code);
			assertThat(busyLedger().shuffled(1).analyse(code)).isEqualTo(base);
			assertThat(busyLedger().shuffled(99).analyse(code)).isEqualTo(base);
		}
	}

	@Test
	void theSameInputGivesTheSameWords() {
		assertThat(busyLedger().analyse(PeriodCode.THIS_MONTH)).isEqualTo(busyLedger().analyse(PeriodCode.THIS_MONTH));
	}

	@Test
	void moreAvailableMoneyNeverLowersTheHealthScore() {
		int previous = -1;
		for (int bank = -500; bank <= 60000; bank += 250) {
			Analysis analysis = busyLedger().balances(String.valueOf(bank), "100", "1200").analyse(PeriodCode.THIS_MONTH);
			int score = analysis.health().score();
			assertThat(score).as("score with ₹%d in the bank", bank).isGreaterThanOrEqualTo(previous);
			previous = score;
		}
		assertThat(previous).isGreaterThan(busyLedger().balances("0", "0", "0").analyse(PeriodCode.THIS_MONTH).health().score());
	}

	@Test
	void wordingVariesWithTheSeedButNeverTheNumbers() {
		Analysis one = busyLedger().seed(1).analyse(PeriodCode.THIS_MONTH);
		boolean differentWords = false;
		for (long seed = 2; seed < 12; seed++) {
			Analysis other = busyLedger().seed(seed).analyse(PeriodCode.THIS_MONTH);
			assertThat(other.totals()).isEqualTo(one.totals());
			assertThat(other.health()).isEqualTo(one.health());
			assertThat(other.insights()).extracting(Analysis.Insight::id)
				.containsExactlyElementsOf(one.insights().stream().map(Analysis.Insight::id).toList());
			differentWords |= !other.summary().equals(one.summary());
		}
		assertThat(differentWords).isTrue();
	}

}
