package com.spendanalyzer.insight.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;
import org.junit.jupiter.api.Test;

/** Hand-made ledgers with a fixed "today": each one must produce exactly the finding it was built for. */
class InsightEngineLedgerTest {

	private static Optional<Analysis.Insight> insight(Analysis analysis, InsightType type) {
		return analysis.insights().stream().filter(i -> i.type() == type).findFirst();
	}

	private static Optional<Analysis.Insight> insight(Analysis analysis, String id) {
		return analysis.insights().stream().filter(i -> i.id().equals(id)).findFirst();
	}

	/** Every sentence the user can read. */
	static List<String> allText(Analysis analysis) {
		List<String> texts = new ArrayList<>();
		texts.add(analysis.summary());
		for (Analysis.Insight insight : analysis.insights()) {
			texts.add(insight.title());
			texts.add(insight.text());
		}
		for (Analysis.Tip tip : analysis.tips()) {
			texts.add(tip.text());
		}
		if (analysis.dataQuality().message() != null) {
			texts.add(analysis.dataQuality().message());
		}
		if (analysis.health() != null) {
			analysis.health().components().forEach(component -> texts.add(component.note()));
		}
		return texts;
	}

	// ------------------------------------------------------------------ leak

	private static Ledger leakLedger() {
		return Ledger.on("2026-11-28")
			.everyDay("2026-11-01", "2026-11-28", "80", Category.FOOD_MEALS, "lunch")
			.everyDay("2026-11-01", "2026-11-14", "40", Category.SNACKS_BEVERAGES, "juice")
			.balances("5000", "500", "0");
	}

	@Test
	void fourteenJuicesAreASmallLeak() {
		Analysis analysis = leakLedger().analyse(PeriodCode.THIS_MONTH);

		Analysis.Insight leak = insight(analysis, InsightType.SMALL_LEAK).orElseThrow();
		assertThat(leak.id()).isEqualTo("leak-juice");
		assertThat(leak.title()).isEqualTo("Juice adds up");
		assertThat(leak.text()).contains("juice").contains("14 times").contains("₹560").contains("this month");
		assertThat(leak.severity()).isEqualTo(Severity.WARN); // 560 of 2,800 is a fifth of the spending

		Analysis.TopItem juice = analysis.topItems().stream().filter(i -> i.item().equals("juice")).findFirst().orElseThrow();
		assertThat(juice.count()).isEqualTo(14);
		assertThat(juice.amount()).isEqualTo("560.00");
		assertThat(juice.category()).isEqualTo("SNACKS_BEVERAGES");

		// 14 in 28 days is 3.5 a week; cap at 1 saves 2.5 x 40 x 30/7 = 428.57 -> about 430 a month
		Analysis.Tip tip = analysis.tips().stream().filter(t -> t.text().contains("juice")).findFirst().orElseThrow();
		assertThat(tip.text()).isEqualTo("Cap juice at 1 a week: saves about ₹430 a month.");
		assertThat(tip.saving()).isEqualTo("430.00");
	}

	@Test
	void leakTipUsesTheRateSinceTrackingBegan() {
		// six juices in five tracked days is about eight a week, not "1.4 a week" over 30 calendar days
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-11-06", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.everyDay("2026-11-06", "2026-11-10", "40", Category.SNACKS_BEVERAGES, "juice")
			.spend("2026-11-10", "40", Category.SNACKS_BEVERAGES, "juice")
			.analyse(PeriodCode.LAST_30_DAYS);

		assertThat(insight(analysis, "leak-juice")).isPresent();
		// under two tracked weeks there is no "a week" or "a month" to talk about
		assertThat(analysis.tips()).extracting(Analysis.Tip::text)
			.contains("Buying juice half as often would have saved about ₹120 in the last 30 days.")
			.noneMatch(text -> text.contains("a week"));
	}

	@Test
	void leakTipCapsAtHalfTheTrackedWeeklyRate() {
		// 12 juices in 21 tracked days is 4 a week; cap at 2 saves 2 x 40 x 30/7 = 342.86 -> about 340 a month
		Analysis analysis = Ledger.on("2026-11-21")
			.everyDay("2026-11-01", "2026-11-21", "100", Category.FOOD_MEALS, "meals")
			.everyDay("2026-11-01", "2026-11-12", "40", Category.SNACKS_BEVERAGES, "juice")
			.analyse(PeriodCode.LAST_30_DAYS);

		Analysis.Tip tip = analysis.tips().stream().filter(t -> t.text().contains("juice")).findFirst().orElseThrow();
		assertThat(tip.text()).isEqualTo("Cap juice at 2 a week: saves about ₹340 a month.");
		assertThat(tip.saving()).isEqualTo("340.00");
	}

	@Test
	void aNeedBoughtEveryDayIsNotALeak() {
		Analysis analysis = leakLedger().analyse(PeriodCode.THIS_MONTH);
		assertThat(analysis.insights()).noneMatch(i -> i.id().equals("leak-lunch"));
	}

	@Test
	void fourBuysAreNotYetALeak() {
		Analysis analysis = Ledger.on("2026-11-28")
			.everyDay("2026-11-01", "2026-11-28", "80", Category.FOOD_MEALS, "lunch")
			.everyDay("2026-11-01", "2026-11-04", "60", Category.SNACKS_BEVERAGES, "juice")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(analysis, InsightType.SMALL_LEAK)).isEmpty();
	}

	// ------------------------------------------------------------------ runway

	@Test
	void twoThousandAtAHundredADayLastsTwentyDays() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-10-12", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("1500", "500", "0")
			.analyse(PeriodCode.THIS_MONTH);

		Analysis.Runway runway = analysis.runway();
		assertThat(runway.available()).isEqualTo("2000.00");
		assertThat(runway.dailyBurn()).isEqualTo("100.00");
		assertThat(runway.daysLeft()).isEqualTo(20);
		assertThat(runway.daysToMonthEnd()).isEqualTo(20);
		assertThat(runway.safePerDay()).isEqualTo("100.00");

		Analysis.Insight insight = insight(analysis, InsightType.RUNWAY).orElseThrow();
		assertThat(insight.text()).contains("₹100 a day").contains("₹2,000").contains("20 days")
			.contains("assuming no new money comes in");
	}

	@Test
	void runwayWarnsAndTellsWhatIsSafeToSpend() {
		// 2,150 at 165 a day lasts 13 days, but the month still has 20 days
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-10-12", "2026-11-10", "165", Category.FOOD_MEALS, "meals")
			.balances("2000", "150", "0")
			.analyse(PeriodCode.THIS_MONTH);

		assertThat(analysis.runway().daysLeft()).isEqualTo(13);
		assertThat(analysis.runway().safePerDay()).isEqualTo("107.50");
		Analysis.Insight insight = insight(analysis, InsightType.RUNWAY).orElseThrow();
		assertThat(insight.severity()).isEqualTo(Severity.WARN);
		assertThat(analysis.insights().get(0)).isEqualTo(insight); // the most important thing comes first
		assertThat(analysis.tips()).extracting(Analysis.Tip::text)
			.contains("To make ₹2,150 last till 30 Nov, keep daily spend under ₹107.");
		Analysis.Component component = analysis.health().components().get(0);
		assertThat(component.name()).isEqualTo("Runway");
		assertThat(component.note()).isEqualTo("Money lasts about 13 days");
	}

	@Test
	void runwayIsAnAlertUnderAWeekAndZeroWhenNothingIsLeft() {
		Ledger ledger = Ledger.on("2026-11-10").everyDay("2026-10-12", "2026-11-10", "100", Category.FOOD_MEALS, "meals");

		Analysis low = ledger.balances("300", "250", "0").analyse(PeriodCode.THIS_MONTH);
		assertThat(low.runway().daysLeft()).isEqualTo(5);
		assertThat(insight(low, InsightType.RUNWAY).orElseThrow().severity()).isEqualTo(Severity.ALERT);

		Analysis empty = ledger.balances("0", "0", "0").analyse(PeriodCode.THIS_MONTH);
		assertThat(empty.runway().daysLeft()).isZero();
		assertThat(empty.runway().safePerDay()).isEqualTo("0.00");
		assertThat(insight(empty, InsightType.RUNWAY).orElseThrow().severity()).isEqualTo(Severity.ALERT);

		Analysis overdrawn = ledger.balances("-200", "50", "0").analyse(PeriodCode.THIS_MONTH);
		assertThat(overdrawn.runway().daysLeft()).isZero();
		assertThat(overdrawn.runway().available()).isEqualTo("-150.00");
	}

	@Test
	void runwayIsCappedAtAYear() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-10-12", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("900000", "0", "0")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(analysis.runway().daysLeft()).isEqualTo(365);
		assertThat(insight(analysis, InsightType.RUNWAY).orElseThrow().text()).contains("more than a year");
	}

	// ------------------------------------------------------------------ per-day average

	@Test
	void perDayAverageCountsOnlyTheDaysSinceTrackingBegan() {
		// first entries ever on 8 Nov: twelve spends of 100 on 8, 9 and 10 Nov
		Ledger ledger = Ledger.on("2026-11-10").balances("6000", "0", "0");
		for (String day : List.of("2026-11-08", "2026-11-09", "2026-11-10")) {
			for (String item : List.of("breakfast", "lunch", "tiffin", "dinner")) {
				ledger.spend(day, "100", Category.FOOD_MEALS, item);
			}
		}
		for (PeriodCode code : List.of(PeriodCode.THIS_MONTH, PeriodCode.LAST_30_DAYS)) {
			Analysis analysis = ledger.analyse(code);
			assertThat(analysis.totals().spent()).isEqualTo("1200.00");
			assertThat(analysis.totals().avgPerDay()).as(code.name()).isEqualTo("400.00");
			assertThat(analysis.summary()).as(code.name()).contains("about ₹400 a day");
			// the same pace as the runway block and its sentence in the same response
			assertThat(analysis.runway().dailyBurn()).isEqualTo("400.00");
			assertThat(insight(analysis, InsightType.RUNWAY).orElseThrow().text()).contains("₹400 a day");
		}
		// the calendar period itself is reported as before
		assertThat(ledger.analyse(PeriodCode.THIS_MONTH).period().days()).isEqualTo(10);
		assertThat(ledger.analyse(PeriodCode.LAST_30_DAYS).period().days()).isEqualTo(30);
	}

	@Test
	void lastMonthAveragesFromTheFirstTrackedDay() {
		// first entry ever on 21 Oct: eleven tracked days of October
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-10-21", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.analyse(PeriodCode.LAST_MONTH);
		assertThat(analysis.period().days()).isEqualTo(31);
		assertThat(analysis.totals().spent()).isEqualTo("1100.00");
		assertThat(analysis.totals().avgPerDay()).isEqualTo("100.00");
		assertThat(analysis.summary()).contains("about ₹100 a day");
	}

	@Test
	void perDayAverageIsAlwaysAPlainNumber() {
		// tracking began after the chosen period: nothing was spent in it
		Analysis afterwards = Ledger.on("2026-11-10")
			.everyDay("2026-11-03", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.analyse(PeriodCode.LAST_MONTH);
		assertThat(afterwards.totals().avgPerDay()).isEqualTo("0.00");

		// input without a usable first-entry date falls back to the calendar days of the period
		LocalDate today = LocalDate.parse("2026-11-10");
		List<Expense> rows = Ledger.on("2026-11-10")
			.everyDay("2026-11-06", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.rows();
		for (LocalDate since : Arrays.asList(null, today.plusDays(5))) {
			for (PeriodCode code : PeriodCode.values()) {
				PeriodWindow window = PeriodWindow.resolve(code, today, since);
				Analysis analysis = new InsightEngine()
					.analyse(LedgerSnapshot.of(window, today, rows, since, Balances.NONE, 42, Ledger.GENERATED_AT));
				BigDecimal expected = new BigDecimal(analysis.totals().spent()).divide(BigDecimal.valueOf(window.days()), 2,
						RoundingMode.HALF_UP);
				assertThat(analysis.totals().avgPerDay()).as("%s since %s", code, since).isEqualTo(expected.toPlainString());
				assertThat(allText(analysis)).noneMatch(text -> text.contains("NaN") || text.contains("Infinity"));
			}
		}
	}

	// ------------------------------------------------------------------ sparse and empty

	@Test
	void twoExpensesGiveOnlyAFriendlyNote() {
		Analysis analysis = Ledger.on("2026-11-10")
			.spend("2026-11-08", "40", Category.SNACKS_BEVERAGES, "juice")
			.spend("2026-11-09", "100", Category.FOOD_MEALS, "meals")
			.balances("1000", "200", "0")
			.analyse(PeriodCode.THIS_MONTH);

		assertThat(analysis.health()).isNull();
		assertThat(analysis.runway()).isNull();
		assertThat(analysis.forecast()).isNull();
		assertThat(analysis.totals().spent()).isEqualTo("140.00");
		assertThat(analysis.totals().count()).isEqualTo(2);
		assertThat(analysis.totals().previousSpent()).isNull();
		assertThat(analysis.totals().changePercent()).isNull();
		assertThat(analysis.summary())
			.isEqualTo("You have recorded 2 spends this month (₹140 in total); add a few more to unlock the full analysis.");
		assertThat(analysis.dataQuality().enoughData()).isFalse();
		assertThat(analysis.dataQuality().message()).isEqualTo("Add 3 more spends to unlock trends");
		assertThat(analysis.insights()).isEmpty();
		assertThat(analysis.tips()).isEmpty();
		assertThat(allText(analysis)).noneMatch(text -> text.contains("%"));
	}

	@Test
	void fourExpensesGetAHedgedSummaryWithoutPercentages() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-11-07", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.analyse(PeriodCode.THIS_MONTH);
		// four tracked days (7 to 10 Nov), not the ten calendar days of the month so far
		assertThat(analysis.summary()).startsWith("So far there are 4 purchases this month: ₹400 in total, about ₹100 a day.")
			.endsWith("With more entries the picture will get sharper.");
		assertThat(analysis.dataQuality().message()).isEqualTo("Add 1 more spend to unlock trends");
		assertThat(allText(analysis)).noneMatch(text -> text.contains("%"));
		assertThat(analysis.health()).isNull();
	}

	@Test
	void noPercentagesOnATinyBaseEvenWithEnoughExpenses() {
		// 16 teas and snacks, 160 rupees in all: enough expenses, but under the ₹200 base for percentages
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-11-03", "2026-11-10", "10", Category.SNACKS_BEVERAGES, "tea")
			.everyDay("2026-11-03", "2026-11-10", "10", Category.FOOD_MEALS, "bun")
			.balances("500", "0", "0")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(analysis.totals().spent()).isEqualTo("160.00");
		assertThat(analysis.health()).isNotNull();
		assertThat(allText(analysis)).noneMatch(text -> text.contains("%"));
		// the structured shares are plain arithmetic and stay
		assertThat(analysis.byCategory().get(0).percent()).isEqualTo(50.0);
	}

	@Test
	void aComparisonOnTwoSmallSamplesIsHedged() {
		// Monday to Wednesday this week against the same days last week, six expenses each
		Ledger ledger = Ledger.on("2026-11-11").spend("2026-10-01", "100", Category.FOOD_MEALS, "meals");
		for (String day : List.of("2026-11-02", "2026-11-03", "2026-11-04")) {
			ledger.spend(day, "100", Category.FOOD_MEALS, "meals").spend(day, "50", Category.FOOD_MEALS, "tiffin");
		}
		for (String day : List.of("2026-11-09", "2026-11-10", "2026-11-11")) {
			ledger.spend(day, "200", Category.FOOD_MEALS, "meals").spend(day, "100", Category.FOOD_MEALS, "tiffin");
		}
		Analysis analysis = ledger.analyse(PeriodCode.THIS_WEEK);
		assertThat(analysis.totals().changePercent()).isEqualTo(100.0);
		assertThat(insight(analysis, "change-overall").orElseThrow().text()).isEqualTo(
				"Early sign: so far you have spent 100% more than in the same days last week, ₹900 against ₹450.");
	}

	@Test
	void emptyLedgerIsAValidAnalysisForEveryPeriod() {
		for (PeriodCode code : PeriodCode.values()) {
			Analysis analysis = Ledger.on("2026-11-10").analyse(code);

			assertThat(analysis.period().code()).isEqualTo(code.name());
			assertThat(analysis.totals().spent()).isEqualTo("0.00");
			assertThat(analysis.totals().count()).isZero();
			assertThat(analysis.totals().avgPerDay()).isEqualTo("0.00");
			assertThat(analysis.totals().biggestDay()).isNull();
			assertThat(analysis.totals().biggestSpend()).isNull();
			assertThat(analysis.totals().previousSpent()).isNull();
			assertThat(analysis.totals().changePercent()).isNull();
			assertThat(analysis.byCategory()).isEmpty();
			assertThat(analysis.byMode()).isEmpty();
			assertThat(analysis.topItems()).isEmpty();
			assertThat(analysis.daily()).hasSize(analysis.period().days()).allMatch(d -> d.amount().equals("0.00"));
			assertThat(analysis.needsWants()).isNull();
			assertThat(analysis.runway()).isNull();
			assertThat(analysis.forecast()).isNull();
			assertThat(analysis.health()).isNull();
			assertThat(analysis.insights()).isEmpty();
			assertThat(analysis.tips()).isEmpty();
			assertThat(analysis.summary()).isNotBlank().doesNotContain("%").doesNotContain("NaN");
			assertThat(analysis.dataQuality().needsReviewCount()).isZero();
			assertThat(analysis.dataQuality().enoughData()).isFalse();
			assertThat(analysis.dataQuality().message()).isNotBlank();
			assertThat(analysis.generatedAt()).isEqualTo("2026-11-10T08:15:30Z");
		}
		assertThat(Ledger.on("2026-11-10").analyse(PeriodCode.THIS_MONTH).summary())
			.isEqualTo("No spends recorded this month yet; add a few and your analysis will appear here.");
	}

	// ------------------------------------------------------------------ spike

	private static Ledger spikeLedger() {
		return Ledger.on("2026-11-10")
			.everyDay("2026-10-12", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.spend("2026-11-05", "3000", Category.FOOD_MEALS, "party dinner")
			.balances("4000", "0", "0");
	}

	@Test
	void oneHugePurchaseIsCalledUnusual() {
		Analysis analysis = spikeLedger().analyse(PeriodCode.THIS_MONTH);

		Analysis.Insight unusual = insight(analysis, InsightType.UNUSUAL_SPEND).orElseThrow();
		assertThat(unusual.text())
			.isEqualTo("₹3,000 on party dinner on 5 Nov is about 30 times your usual Food & Meals spend of ₹100.");
		assertThat(analysis.totals().biggestSpend().description()).isEqualTo("party dinner");
		assertThat(analysis.totals().biggestSpend().amount()).isEqualTo("3000.00");
		assertThat(analysis.totals().biggestDay().date()).isEqualTo("2026-11-05");
		assertThat(analysis.totals().biggestDay().amount()).isEqualTo("3100.00");
	}

	@Test
	void oneHugePurchaseDoesNotDominateTheBurn() {
		Analysis analysis = spikeLedger().analyse(PeriodCode.THIS_MONTH);
		// the plain mean would be 6,000 / 30 = 200 a day
		BigDecimal burn = new BigDecimal(analysis.runway().dailyBurn());
		assertThat(burn).isBetween(new BigDecimal("100"), new BigDecimal("125"));
		// and the health score shows the spike for what it is
		Analysis.Component spikes = analysis.health().components().stream().filter(c -> c.name().equals("Spikes")).findFirst().orElseThrow();
		assertThat(spikes.points()).isZero();
		assertThat(spikes.note()).isEqualTo("1 unusually large spend in the last 30 days");
	}

	@Test
	void ordinaryVariationIsNotUnusual() {
		Ledger ledger = Ledger.on("2026-11-10").balances("4000", "0", "0");
		String[] prices = { "60", "80", "100", "120", "150" };
		LocalDate day = LocalDate.parse("2026-10-12");
		for (int i = 0; i < 30; i++) {
			ledger.spend(day.plusDays(i), prices[i % prices.length], Category.FOOD_MEALS, "meals");
		}
		Analysis analysis = ledger.analyse(PeriodCode.LAST_30_DAYS);
		assertThat(insight(analysis, InsightType.UNUSUAL_SPEND)).isEmpty();
	}

	@Test
	void aLoneBigWantIsComparedWithAllSpendsButABigNeedIsNot() {
		Ledger ledger = Ledger.on("2026-11-10")
			.everyDay("2026-10-12", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.spend("2026-11-03", "2500", Category.SHOPPING, "shoes")
			.spend("2026-11-05", "4500", Category.RENT_UTILITIES, "rent") // first rent: large by nature
			.balances("9000", "0", "0");
		Analysis analysis = ledger.analyse(PeriodCode.THIS_MONTH);

		List<Analysis.Insight> unusual = analysis.insights().stream().filter(i -> i.type() == InsightType.UNUSUAL_SPEND).toList();
		assertThat(unusual).hasSize(1);
		assertThat(unusual.get(0).text())
			.isEqualTo("₹2,500 on shoes on 3 Nov stands out: about 25 times your typical spend of ₹100.");
	}

	@Test
	void aGoodPeriodGetsGoodNews() {
		Analysis analysis = Ledger.on("2026-11-28")
			.everyDay("2026-10-01", "2026-10-31", "150", Category.FOOD_MEALS, "meals")
			.everyDay("2026-11-01", "2026-11-28", "100", Category.FOOD_MEALS, "meals")
			.balances("9000", "0", "0")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(analysis.insights()).anyMatch(i -> i.severity() == Severity.GOOD);
		assertThat(analysis.insights()).anyMatch(i -> i.type() == InsightType.POSITIVE);
	}

	@Test
	void noSpendDaysAreMentionedOnceTrackingHasStarted() {
		Ledger ledger = Ledger.on("2026-11-28").spend("2026-10-01", "100", Category.FOOD_MEALS, "meals");
		for (LocalDate day = LocalDate.parse("2026-11-01"); !day.isAfter(ledger.today()); day = day.plusDays(1)) {
			if (day.getDayOfMonth() % 4 != 0) { // every fourth day nothing is spent: 7 quiet days of 28
				ledger.spend(day, "100", Category.FOOD_MEALS, "meals");
			}
		}
		Analysis analysis = ledger.analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(analysis, "positive-no-spend-days").orElseThrow().text())
			.isEqualTo("7 of the 28 days so far this month had no spending recorded.");
	}

	// ------------------------------------------------------------------ weekend pattern

	private static Ledger weekendLedger(String weekdayAmount, String weekendAmount) {
		// 28 Nov 2026 is a Saturday; the ledger starts on Monday 19 Oct: five full weeks before this week
		Ledger ledger = Ledger.on("2026-11-28").balances("20000", "0", "0");
		for (LocalDate day = LocalDate.parse("2026-10-19"); !day.isAfter(ledger.today()); day = day.plusDays(1)) {
			boolean weekend = day.getDayOfWeek().getValue() >= 6;
			ledger.spend(day, weekend ? weekendAmount : weekdayAmount, Category.FOOD_MEALS, "meals");
		}
		return ledger;
	}

	@Test
	void weekendsThatCostFourTimesAWeekdayAreAPattern() {
		Analysis analysis = weekendLedger("100", "400").analyse(PeriodCode.THIS_MONTH);
		Analysis.Insight pattern = insight(analysis, InsightType.WEEK_PATTERN).orElseThrow();
		assertThat(pattern.title()).isEqualTo("Weekends cost more");
		assertThat(pattern.text()).isEqualTo(
				"Over the last 5 full weeks you spent about ₹400 a day on weekends and ₹100 a day on weekdays.");
		// (400 - 100) on about 8 weekend days a month
		assertThat(analysis.tips()).extracting(Analysis.Tip::text)
			.contains("Matching your weekday pace on weekends would save about ₹2,400 a month.");
	}

	@Test
	void evenSpendingHasNoWeekPattern() {
		assertThat(insight(weekendLedger("100", "120").analyse(PeriodCode.THIS_MONTH), InsightType.WEEK_PATTERN)).isEmpty();
	}

	@Test
	void twoWeeksAreTooFewForAWeekPattern() {
		Ledger ledger = Ledger.on("2026-11-28");
		for (LocalDate day = LocalDate.parse("2026-11-09"); !day.isAfter(ledger.today()); day = day.plusDays(1)) {
			ledger.spend(day, day.getDayOfWeek().getValue() >= 6 ? "400" : "100", Category.FOOD_MEALS, "meals");
		}
		assertThat(insight(ledger.analyse(PeriodCode.THIS_MONTH), InsightType.WEEK_PATTERN)).isEmpty();
	}

	// ------------------------------------------------------------------ recurring

	private static Ledger rentLedger() {
		return Ledger.on("2026-11-10")
			.everyDay("2026-08-20", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.spend("2026-09-05", "4500", Category.RENT_UTILITIES, "rent")
			.spend("2026-10-05", "4500", Category.RENT_UTILITIES, "rent")
			.spend("2026-11-05", "4500", Category.RENT_UTILITIES, "rent")
			.balances("20000", "0", "0");
	}

	@Test
	void threeMonthlyRentsAreRecurring() {
		Analysis analysis = rentLedger().analyse(PeriodCode.THIS_MONTH);

		Analysis.Insight recurring = insight(analysis, InsightType.RECURRING).orElseThrow();
		assertThat(recurring.id()).isEqualTo("recurring-rent");
		assertThat(recurring.title()).isEqualTo("Rent repeats every month");
		// gaps of 30 and 31 days: typical gap 30.5, so the next one is 31 days after 5 Nov
		assertThat(recurring.text()).isEqualTo(
				"Rent comes back every month: ₹4,500 each time, 3 times in the last four months. The next one may fall around 6 Dec.");
		// rent is a known bill, not an unusual spend
		assertThat(insight(analysis, InsightType.UNUSUAL_SPEND)).isEmpty();
	}

	@Test
	void recurringBillIsSpreadOverTheMonthInTheBurn() {
		Analysis analysis = rentLedger().analyse(PeriodCode.THIS_MONTH);
		// everyday 100 + rent 4,500 / 31 days = 245.16
		assertThat(analysis.runway().dailyBurn()).isEqualTo("245.16");
		// spent so far 5,500 + 20 days x 100, and no further rent before 30 Nov
		assertThat(analysis.forecast().monthEndExpected()).isEqualTo("7500.00");
	}

	@Test
	void twoRentsAreNotYetRecurring() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-08-20", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.spend("2026-10-05", "4500", Category.RENT_UTILITIES, "rent")
			.spend("2026-11-05", "4500", Category.RENT_UTILITIES, "rent")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(analysis, InsightType.RECURRING)).isEmpty();
	}

	@Test
	void aDueBillGetsATip() {
		Analysis analysis = Ledger.on("2026-12-01")
			.everyDay("2026-08-20", "2026-12-01", "100", Category.FOOD_MEALS, "meals")
			.spend("2026-09-05", "4500", Category.RENT_UTILITIES, "rent")
			.spend("2026-10-05", "4500", Category.RENT_UTILITIES, "rent")
			.spend("2026-11-05", "4500", Category.RENT_UTILITIES, "rent")
			.balances("20000", "0", "0")
			.analyse(PeriodCode.LAST_30_DAYS);
		assertThat(analysis.tips()).extracting(Analysis.Tip::text).contains("Keep ₹4,500 aside for rent around 6 Dec.");
	}

	// ------------------------------------------------------------------ period over period

	@Test
	void spendingUpAgainstTheSameDaysOfLastMonth() {
		Analysis analysis = Ledger.on("2026-11-20")
			.everyDay("2026-10-01", "2026-10-31", "100", Category.FOOD_MEALS, "meals")
			.everyDay("2026-11-01", "2026-11-20", "150", Category.FOOD_MEALS, "meals")
			.balances("9000", "0", "0")
			.analyse(PeriodCode.THIS_MONTH);

		assertThat(analysis.totals().spent()).isEqualTo("3000.00");
		assertThat(analysis.totals().previousSpent()).isEqualTo("2000.00"); // 1 to 20 Oct only
		assertThat(analysis.totals().changePercent()).isEqualTo(50.0);

		Analysis.Insight change = insight(analysis, "change-overall").orElseThrow();
		assertThat(change.type()).isEqualTo(InsightType.PERIOD_CHANGE);
		assertThat(change.severity()).isEqualTo(Severity.WARN);
		assertThat(change.text()).contains("50%").contains("₹3,000").contains("₹2,000").contains("the same days last month");
		assertThat(insight(analysis, "change-food-meals").orElseThrow().text())
			.isEqualTo("Food & Meals is up 50% on the same days last month: ₹3,000 against ₹2,000.");
		assertThat(analysis.summary()).contains("That is 50% more than in the same days last month (₹2,000).");
	}

	@Test
	void spendingDownIsGoodNews() {
		Analysis analysis = Ledger.on("2026-11-20")
			.everyDay("2026-10-01", "2026-10-31", "150", Category.FOOD_MEALS, "meals")
			.everyDay("2026-11-01", "2026-11-20", "100", Category.FOOD_MEALS, "meals")
			.balances("9000", "0", "0")
			.analyse(PeriodCode.THIS_MONTH);

		assertThat(analysis.totals().changePercent()).isEqualTo(-33.3);
		Analysis.Insight down = insight(analysis, "positive-spending-down").orElseThrow();
		assertThat(down.type()).isEqualTo(InsightType.POSITIVE);
		assertThat(down.severity()).isEqualTo(Severity.GOOD);
		assertThat(down.text()).contains("33%").contains("₹2,000").contains("₹3,000");
		assertThat(insight(analysis, "change-overall")).isEmpty();
	}

	@Test
	void aSmallChangeIsNotReported() {
		Analysis analysis = Ledger.on("2026-11-20")
			.everyDay("2026-10-01", "2026-10-31", "100", Category.FOOD_MEALS, "meals")
			.everyDay("2026-11-01", "2026-11-20", "110", Category.FOOD_MEALS, "meals")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(analysis.totals().changePercent()).isEqualTo(10.0);
		assertThat(insight(analysis, InsightType.PERIOD_CHANGE)).isEmpty();
		assertThat(insight(analysis, "positive-spending-down")).isEmpty();
	}

	@Test
	void noComparisonWithAPeriodThatWasOnlyPartlyTracked() {
		// The first entry ever is on 15 Oct: "1 to 20 Oct" holds six days of data, not twenty.
		Analysis analysis = Ledger.on("2026-11-20")
			.everyDay("2026-10-15", "2026-10-31", "100", Category.FOOD_MEALS, "meals")
			.everyDay("2026-11-01", "2026-11-20", "100", Category.FOOD_MEALS, "meals")
			.analyse(PeriodCode.THIS_MONTH);

		assertThat(analysis.totals().previousSpent()).isEqualTo("600.00");
		assertThat(analysis.totals().changePercent()).isNull();
		assertThat(insight(analysis, InsightType.PERIOD_CHANGE)).isEmpty();
		assertThat(analysis.summary()).doesNotContain("more than");
	}

	@Test
	void noPercentageOnATinyBase() {
		// previous period: five teas, 50 rupees in all
		Analysis analysis = Ledger.on("2026-11-20")
			.everyDay("2026-10-01", "2026-10-05", "10", Category.SNACKS_BEVERAGES, "tea")
			.everyDay("2026-11-01", "2026-11-20", "100", Category.FOOD_MEALS, "meals")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(analysis.totals().previousSpent()).isEqualTo("50.00");
		assertThat(analysis.totals().changePercent()).isNull();
		assertThat(insight(analysis, "change-overall")).isEmpty();
	}

	// ------------------------------------------------------------------ the first days of a week or month

	/**
	 * Meals at the same pace as last week: three so far on every day of this week (today is not over
	 * yet), four on every full day of last week. 9 Nov 2026 is a Monday.
	 */
	private static Ledger samePaceLedger(String today) {
		Ledger ledger = Ledger.on(today).spend("2026-10-01", "75", Category.FOOD_MEALS, "meals").balances("9000", "0", "0");
		for (LocalDate day = LocalDate.parse("2026-11-02"); !day.isAfter(ledger.today()); day = day.plusDays(1)) {
			for (int i = 0; i < (day.isBefore(LocalDate.parse("2026-11-09")) ? 4 : 3); i++) {
				ledger.spend(day, "75", Category.FOOD_MEALS, "meals");
			}
		}
		return ledger;
	}

	@Test
	void noDownwardClaimInTheFirstThreeDaysOfAPeriodToDate() {
		// Tuesday: 6 spends (₹450) against 8 (₹600) on last Monday and Tuesday
		Analysis analysis = samePaceLedger("2026-11-10").analyse(PeriodCode.THIS_WEEK);

		// the numbers are plain arithmetic and stay
		assertThat(analysis.totals().spent()).isEqualTo("450.00");
		assertThat(analysis.totals().previousSpent()).isEqualTo("600.00");
		assertThat(analysis.totals().changePercent()).isEqualTo(-25.0);
		// but an unfinished Tuesday against a full one is no reason to say "you are spending less"
		assertThat(insight(analysis, "positive-spending-down")).isEmpty();
		assertThat(insight(analysis, InsightType.PERIOD_CHANGE)).isEmpty();
		assertThat(analysis.summary()).doesNotContain("less than in").doesNotContain("last week");
		assertThat(allText(analysis)).noneMatch(text -> text.contains("is down") || text.contains("Early sign"));
	}

	@Test
	void theDownwardClaimReturnsOnTheFourthDay() {
		// Thursday: 12 spends (₹900) against 16 (₹1,200)
		Analysis analysis = samePaceLedger("2026-11-12").analyse(PeriodCode.THIS_WEEK);

		assertThat(analysis.totals().changePercent()).isEqualTo(-25.0);
		Analysis.Insight down = insight(analysis, "positive-spending-down").orElseThrow();
		assertThat(down.text()).contains("25%").contains("₹900").contains("₹1,200");
		assertThat(analysis.summary()).contains("That is 25% less than in the same days last week (₹1,200).");
	}

	@Test
	void earlyInAPeriodOnlyARisingCategoryIsReported() {
		// Tuesday: food is behind last Monday and Tuesday (today is not over), snacks are new this week
		Ledger ledger = Ledger.on("2026-11-10").spend("2026-10-01", "75", Category.FOOD_MEALS, "meals");
		for (String day : List.of("2026-11-02", "2026-11-03")) {
			for (int i = 0; i < 5; i++) {
				ledger.spend(day, "75", Category.FOOD_MEALS, "meals");
			}
		}
		for (String day : List.of("2026-11-09", "2026-11-10")) {
			for (int i = 0; i < 3; i++) {
				ledger.spend(day, "75", Category.FOOD_MEALS, "meals");
			}
			ledger.spend(day, "100", Category.SNACKS_BEVERAGES, "cake");
		}
		Analysis analysis = ledger.analyse(PeriodCode.THIS_WEEK);

		assertThat(analysis.totals().spent()).isEqualTo("650.00");
		assertThat(analysis.totals().previousSpent()).isEqualTo("750.00");
		// food moved most in rupees (-300), but "down" is not said this early; the rise of 200 is
		assertThat(insight(analysis, "change-food-meals")).isEmpty();
		assertThat(insight(analysis, "change-snacks-beverages").orElseThrow().text()).isEqualTo(
				"You spent ₹200 on Snacks & Beverages this week, and nothing on it in the same days last week.");
	}

	// ------------------------------------------------------------------ bills and the comparison

	@Test
	void anUnpaidNeedIsNotGoodNews() {
		// rent was paid on 3 Oct; on 4 Nov this month's rent is not paid yet. One rent is not yet a known bill.
		Analysis analysis = Ledger.on("2026-11-04")
			.everyDay("2026-10-01", "2026-11-04", "100", Category.FOOD_MEALS, "meals")
			.everyDay("2026-10-01", "2026-11-04", "100", Category.FOOD_MEALS, "dinner")
			.spend("2026-10-03", "5000", Category.RENT_UTILITIES, "rent")
			.balances("9000", "0", "0")
			.analyse(PeriodCode.THIS_MONTH);

		Analysis.Insight rent = insight(analysis, "change-rent-utilities").orElseThrow();
		assertThat(rent.text())
			.isEqualTo("You spent nothing on Rent & Utilities this month, against ₹5,000 in the same days last month.");
		assertThat(rent.severity()).isEqualTo(Severity.INFO);
	}

	@Test
	void lessOnAWantIsGoodNewsLessOnANeedIsOnlyStated() {
		Ledger wants = Ledger.on("2026-11-20")
			.everyDay("2026-10-01", "2026-11-20", "100", Category.FOOD_MEALS, "meals")
			.balances("9000", "0", "0");
		for (String day : List.of("2026-10-02", "2026-10-05", "2026-10-06", "2026-10-11", "2026-10-17")) {
			wants.spend(day, "200", Category.ENTERTAINMENT_SUBS, "movie");
		}
		Analysis.Insight movies = insight(wants.analyse(PeriodCode.THIS_MONTH), "change-entertainment-subs").orElseThrow();
		assertThat(movies.title()).isEqualTo("Entertainment & Subscriptions is down");
		assertThat(movies.severity()).isEqualTo(Severity.GOOD);

		Analysis needs = Ledger.on("2026-11-20")
			.everyDay("2026-10-01", "2026-10-31", "150", Category.FOOD_MEALS, "meals")
			.everyDay("2026-11-01", "2026-11-20", "100", Category.FOOD_MEALS, "meals")
			.balances("9000", "0", "0")
			.analyse(PeriodCode.THIS_MONTH);
		Analysis.Insight food = insight(needs, "change-food-meals").orElseThrow();
		assertThat(food.text()).isEqualTo("Food & Meals is down 33% on the same days last month: ₹2,000 against ₹3,000.");
		assertThat(food.severity()).isEqualTo(Severity.INFO);
	}

	/** Meals twice a day since August and rent of ₹4,500 on the given days: a known monthly bill. */
	private static Ledger billLedger(String today, String novemberMeal, String... rentDays) {
		Ledger ledger = Ledger.on(today)
			.everyDay("2026-08-01", "2026-10-31", "100", Category.FOOD_MEALS, "meals")
			.everyDay("2026-08-01", "2026-10-31", "100", Category.FOOD_MEALS, "dinner")
			.everyDay("2026-11-01", today, novemberMeal, Category.FOOD_MEALS, "meals")
			.everyDay("2026-11-01", today, novemberMeal, Category.FOOD_MEALS, "dinner")
			.balances("30000", "0", "0");
		for (String day : rentDays) {
			ledger.spend(day, "4500", Category.RENT_UTILITIES, "rent");
		}
		return ledger;
	}

	@Test
	void aBillNotYetPaidDoesNotMakeSpendingLookDown() {
		// 4 Nov: rent came on 3 Aug, 3 Sep and 3 Oct, but not yet this month; everyday spending is unchanged
		Analysis analysis = billLedger("2026-11-04", "100", "2026-08-03", "2026-09-03", "2026-10-03")
			.analyse(PeriodCode.THIS_MONTH);

		// the totals are plain arithmetic and stay
		assertThat(analysis.totals().spent()).isEqualTo("800.00");
		assertThat(analysis.totals().previousSpent()).isEqualTo("5300.00");
		assertThat(analysis.totals().changePercent()).isEqualTo(-84.9);
		assertThat(insight(analysis, "recurring-rent")).isPresent();
		// but the pay date of the rent is not a change in spending
		assertThat(insight(analysis, "positive-spending-down")).isEmpty();
		assertThat(insight(analysis, InsightType.PERIOD_CHANGE)).isEmpty();
		assertThat(allText(analysis)).noneMatch(text -> text.contains("is down") || text.contains("spent nothing on"));
	}

	@Test
	void aBillPaidEarlierThanLastMonthDoesNotMakeSpendingLookUp() {
		// 4 Nov: this month's rent is already paid on the 2nd; last month it came on the 5th
		Analysis analysis = billLedger("2026-11-04", "100", "2026-08-05", "2026-09-05", "2026-10-05", "2026-11-02")
			.analyse(PeriodCode.THIS_MONTH);

		assertThat(analysis.totals().spent()).isEqualTo("5300.00");
		assertThat(analysis.totals().previousSpent()).isEqualTo("800.00");
		assertThat(insight(analysis, "change-overall")).isEmpty();
		assertThat(insight(analysis, InsightType.PERIOD_CHANGE)).isEmpty();
	}

	@Test
	void aRealChangeIsStillReportedWhenTheSameBillFallsInBothPeriods() {
		// 10 Nov: rent on the 3rd in both months, and everyday spending has doubled
		Analysis analysis = billLedger("2026-11-10", "200", "2026-08-03", "2026-09-03", "2026-10-03", "2026-11-03")
			.analyse(PeriodCode.THIS_MONTH);

		assertThat(analysis.totals().changePercent()).isEqualTo(30.8); // 8,500 against 6,500, rent included
		assertThat(insight(analysis, "change-overall").orElseThrow().text()).contains("31%").contains("₹8,500")
			.contains("₹6,500");
		// the category with the bill is not compared; the one that really moved is
		assertThat(insight(analysis, "change-rent-utilities")).isEmpty();
		assertThat(insight(analysis, "change-food-meals").orElseThrow().text())
			.isEqualTo("Food & Meals is up 100% on the same days last month: ₹4,000 against ₹2,000.");
	}

	// ------------------------------------------------------------------ credit card

	@Test
	void creditCardDueAboveAvailableMoneyIsAnAlert() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-11-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("1500", "500", "5000")
			.analyse(PeriodCode.THIS_MONTH);

		Analysis.Insight card = insight(analysis, InsightType.CREDIT_CARD).orElseThrow();
		assertThat(card.severity()).isEqualTo(Severity.ALERT);
		assertThat(card.text()).isEqualTo("Your credit card due of ₹5,000 is more than the ₹2,000 you have in bank and cash.");
		assertThat(analysis.tips()).extracting(Analysis.Tip::text)
			.contains("Set aside ₹5,000 for the credit card bill before other spending.");
	}

	@Test
	void smallCreditCardDueIsJustStated() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-11-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("1500", "500", "300")
			.analyse(PeriodCode.THIS_MONTH);
		Analysis.Insight card = insight(analysis, InsightType.CREDIT_CARD).orElseThrow();
		assertThat(card.severity()).isEqualTo(Severity.INFO);
		assertThat(card.text()).isEqualTo("You owe ₹300 on your credit card, against ₹2,000 in bank and cash.");
	}

	@Test
	void noCreditCardInsightWithoutADue() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-11-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("1500", "500", "0")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(analysis, InsightType.CREDIT_CARD)).isEmpty();
	}

	// ------------------------------------------------------------------ money owed to friends

	/** Ten days of meals at ₹100, bank 5,200 and cash 340: ₹5,540 to spend. */
	private static Ledger studentWithMoney() {
		return Ledger.on("2026-11-10")
			.everyDay("2026-11-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("5200", "340", "0");
	}

	@Test
	void owingTwoFriendsStatesTheTotalWhoAndWhatWouldBeLeft() {
		Analysis analysis = studentWithMoney().owes("Arun", "500").owes("Ravi", "1000").analyse(PeriodCode.THIS_MONTH);

		Analysis.Insight borrowed = insight(analysis, InsightType.BORROWED).orElseThrow();
		assertThat(borrowed.id()).isEqualTo("borrowed");
		assertThat(borrowed.severity()).isEqualTo(Severity.INFO);
		assertThat(borrowed.title()).isEqualTo("Money owed to friends");
		assertThat(borrowed.text()).isEqualTo("You owe friends ₹1,500 in total: Ravi ₹1,000, Arun ₹500. "
				+ "After paying everyone back you would have ₹4,040 in bank and cash.");
		assertThat(analysis.insights()).filteredOn(i -> i.type() == InsightType.BORROWED).hasSize(1);
	}

	@Test
	void owingOneFriendNamesJustThatFriend() {
		Analysis analysis = studentWithMoney().owes("Ravi", "250.50").analyse(PeriodCode.THIS_MONTH);
		Analysis.Insight borrowed = insight(analysis, InsightType.BORROWED).orElseThrow();
		assertThat(borrowed.severity()).isEqualTo(Severity.INFO);
		assertThat(borrowed.text())
			.isEqualTo("You owe Ravi ₹250.50. After paying it back you would have ₹5,289.50 in bank and cash.");
	}

	@Test
	void theThreeLargestDebtsAreNamedAndTheOthersCounted() {
		Analysis four = studentWithMoney().owes("Divya", "100")
			.owes("Ravi", "1000")
			.owes("Chitra", "300")
			.owes("Arun", "600")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(four, InsightType.BORROWED).orElseThrow().text())
			.isEqualTo("You owe friends ₹2,000 in total: Ravi ₹1,000, Arun ₹600, Chitra ₹300 and 1 more. "
					+ "After paying everyone back you would have ₹3,540 in bank and cash.");

		Analysis six = studentWithMoney().owes("Divya", "100")
			.owes("Ravi", "1000")
			.owes("Chitra", "300")
			.owes("Arun", "600")
			.owes("Esha", "20")
			.owes("Farid", "20.50")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(six, InsightType.BORROWED).orElseThrow().text())
			.isEqualTo("You owe friends ₹2,040.50 in total: Ravi ₹1,000, Arun ₹600, Chitra ₹300 and 3 more. "
					+ "After paying everyone back you would have ₹3,499.50 in bank and cash.");

		// exactly three: all named, nobody counted
		Analysis three = studentWithMoney().owes("Ravi", "1000").owes("Chitra", "300").owes("Arun", "600")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(three, InsightType.BORROWED).orElseThrow().text())
			.startsWith("You owe friends ₹1,900 in total: Ravi ₹1,000, Arun ₹600, Chitra ₹300. After");
	}

	@Test
	void aDebtAboveBankAndCashIsAWarningAndSaysSoPlainly() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-11-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("500", "300", "0")
			.owes("Ravi", "1000")
			.owes("Arun", "500")
			.analyse(PeriodCode.THIS_MONTH);
		Analysis.Insight borrowed = insight(analysis, InsightType.BORROWED).orElseThrow();
		assertThat(borrowed.severity()).isEqualTo(Severity.WARN);
		assertThat(borrowed.text()).isEqualTo("You owe friends ₹1,500 in total: Ravi ₹1,000, Arun ₹500. "
				+ "That is more than the ₹800 you have in bank and cash.");

		Analysis one = Ledger.on("2026-11-10")
			.everyDay("2026-11-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("500", "300", "0")
			.owes("Ravi", "800.01")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(one, InsightType.BORROWED).orElseThrow().severity()).isEqualTo(Severity.WARN);
		assertThat(insight(one, InsightType.BORROWED).orElseThrow().text())
			.isEqualTo("You owe Ravi ₹800.01. That is more than the ₹800 you have in bank and cash.");
	}

	@Test
	void aDebtOfExactlyWhatIsThereIsPayableButAWarning() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-11-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("500", "300", "0")
			.owes("Ravi", "800")
			.analyse(PeriodCode.THIS_MONTH);
		Analysis.Insight borrowed = insight(analysis, InsightType.BORROWED).orElseThrow();
		// all of the money is owed, which is more than half of it: a warning, in the words of a debt that can be paid
		assertThat(borrowed.severity()).isEqualTo(Severity.WARN);
		assertThat(borrowed.text()).isEqualTo("You owe Ravi ₹800. After paying it back you would have ₹0 in bank and cash.");
	}

	@Test
	void aDebtAboveHalfOfBankAndCashIsAWarningLikeTheCreditCardDue() {
		// ₹5,540 in bank and cash: exactly half of it is still only good to know ...
		Analysis half = studentWithMoney().owes("Ravi", "2000").owes("Arun", "770").analyse(PeriodCode.THIS_MONTH);
		Analysis.Insight info = insight(half, InsightType.BORROWED).orElseThrow();
		assertThat(info.severity()).isEqualTo(Severity.INFO);
		assertThat(info.text()).isEqualTo("You owe friends ₹2,770 in total: Ravi ₹2,000, Arun ₹770. "
				+ "After paying everyone back you would have ₹2,770 in bank and cash.");

		// ... and one paisa more is a warning, with the same words
		Analysis more = studentWithMoney().owes("Ravi", "2000").owes("Arun", "770.01").analyse(PeriodCode.THIS_MONTH);
		Analysis.Insight warn = insight(more, InsightType.BORROWED).orElseThrow();
		assertThat(warn.severity()).isEqualTo(Severity.WARN);
		assertThat(warn.title()).isEqualTo("Money owed to friends");
		assertThat(warn.text()).isEqualTo("You owe friends ₹2,770.01 in total: Ravi ₹2,000, Arun ₹770.01. "
				+ "After paying everyone back you would have ₹2,769.99 in bank and cash.");

		// the same rule as for the card: a due of that size against the same money is a warning too
		Analysis card = Ledger.on("2026-11-10")
			.everyDay("2026-11-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("5200", "340", "2770.01")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(card, InsightType.CREDIT_CARD).orElseThrow().severity()).isEqualTo(Severity.WARN);
		Analysis cardHalf = Ledger.on("2026-11-10")
			.everyDay("2026-11-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("5200", "340", "2770")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(cardHalf, InsightType.CREDIT_CARD).orElseThrow().severity()).isEqualTo(Severity.INFO);
	}

	// ------------------------------------------------------------------ the runway of borrowed money

	/** Thirty days of meals at ₹100, so ₹100 a day is the burn; everything in the bank, nothing on the card. */
	private static Ledger hundredADay(String bank) {
		return Ledger.on("2026-11-10")
			.everyDay("2026-10-12", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances(bank, "0", "0");
	}

	@Test
	void moneyThatIsMostlyBorrowedIsNotPraisedForLasting() {
		// ₹300 of their own would last 3 days; ₹5,000 from Ravi makes it 53
		Analysis own = hundredADay("5300").analyse(PeriodCode.THIS_MONTH);
		Analysis borrowed = hundredADay("5300").owes("Ravi", "5000").analyse(PeriodCode.THIS_MONTH);

		Analysis.Insight praised = insight(own, InsightType.RUNWAY).orElseThrow();
		assertThat(praised.severity()).isEqualTo(Severity.GOOD);
		assertThat(praised.title()).isEqualTo("Your money has room");

		Analysis.Insight runway = insight(borrowed, InsightType.RUNWAY).orElseThrow();
		assertThat(runway.severity()).isEqualTo(Severity.INFO);
		assertThat(runway.title()).isEqualTo("Money lasts about 53 days");
		// the sentence is about the money that is there today and stays as it was
		assertThat(runway.text()).isEqualTo(praised.text())
			.isEqualTo("At about ₹100 a day, your ₹5,300 in bank and cash lasts about 53 days, assuming no new money comes in.");
		assertThat(allText(borrowed)).noneMatch(text -> text.contains("has room"));

		// the debt is the louder of the two and comes first
		Analysis.Insight debt = insight(borrowed, InsightType.BORROWED).orElseThrow();
		assertThat(debt.severity()).isEqualTo(Severity.WARN);
		assertThat(debt.text()).isEqualTo("You owe Ravi ₹5,000. After paying it back you would have ₹300 in bank and cash.");
		assertThat(borrowed.insights().indexOf(debt)).isLessThan(borrowed.insights().indexOf(runway));

		// no number is touched: the runway block and the health score are about bank and cash
		assertThat(borrowed.runway()).isEqualTo(own.runway());
		assertThat(borrowed.runway().daysLeft()).isEqualTo(53);
		assertThat(borrowed.health()).isEqualTo(own.health());
	}

	@Test
	void theRunwayIsPraisedWhileThirtyDaysAreLeftAfterPayingFriendsBack() {
		// ₹5,000 at ₹100 a day: with ₹2,000 owed, ₹3,000 is left, exactly thirty days
		Analysis enough = hundredADay("5000").owes("Ravi", "1500").owes("Arun", "500").analyse(PeriodCode.THIS_MONTH);
		Analysis.Insight good = insight(enough, InsightType.RUNWAY).orElseThrow();
		assertThat(good.severity()).isEqualTo(Severity.GOOD);
		assertThat(good.title()).isEqualTo("Your money has room");
		assertThat(insight(enough, InsightType.BORROWED).orElseThrow().severity()).isEqualTo(Severity.INFO);

		// one paisa more owed and it is short of thirty days
		Analysis less = hundredADay("5000").owes("Ravi", "1500").owes("Arun", "500.01").analyse(PeriodCode.THIS_MONTH);
		Analysis.Insight info = insight(less, InsightType.RUNWAY).orElseThrow();
		assertThat(info.severity()).isEqualTo(Severity.INFO);
		assertThat(info.title()).isEqualTo("Money lasts about 50 days");
		assertThat(info.text()).isEqualTo(good.text());
		// a debt under half of the money stays good to know: only the praise is gone
		assertThat(insight(less, InsightType.BORROWED).orElseThrow().severity()).isEqualTo(Severity.INFO);
		assertThat(less.runway()).isEqualTo(enough.runway());
		assertThat(less.health()).isEqualTo(enough.health());
	}

	@Test
	void theRunwayFindingOfSomeoneWhoOwesNothingIsUnchanged() {
		for (String bank : new String[] { "-150", "0", "50", "550", "1300", "2000", "2999.99", "3000", "5300", "900000" }) {
			Analysis nothingOwed = hundredADay(bank).analyse(PeriodCode.THIS_MONTH);
			// a friend who was paid back in full is no debt
			Analysis paidBack = hundredADay(bank).owes("Ravi", "0").owes("Arun", "0.00").analyse(PeriodCode.THIS_MONTH);
			assertThat(paidBack).as("bank %s", bank).isEqualTo(nothingOwed);
		}
		// the steps themselves, as they always were
		assertThat(insight(hundredADay("550").analyse(PeriodCode.THIS_MONTH), InsightType.RUNWAY).orElseThrow().severity())
			.isEqualTo(Severity.ALERT);
		assertThat(insight(hundredADay("1300").analyse(PeriodCode.THIS_MONTH), InsightType.RUNWAY).orElseThrow().severity())
			.isEqualTo(Severity.WARN);
		Analysis.Insight twentyNine = insight(hundredADay("2999.99").analyse(PeriodCode.THIS_MONTH), InsightType.RUNWAY)
			.orElseThrow();
		assertThat(twentyNine.severity()).isEqualTo(Severity.INFO);
		assertThat(twentyNine.title()).isEqualTo("Money lasts about 29 days");
		Analysis.Insight thirty = insight(hundredADay("3000").analyse(PeriodCode.THIS_MONTH), InsightType.RUNWAY).orElseThrow();
		assertThat(thirty.severity()).isEqualTo(Severity.GOOD);
		assertThat(thirty.title()).isEqualTo("Your money has room");
		assertThat(thirty.text())
			.isEqualTo("At about ₹100 a day, your ₹3,000 in bank and cash lasts about 30 days, assuming no new money comes in.");
		Analysis.Insight year = insight(hundredADay("900000").analyse(PeriodCode.THIS_MONTH), InsightType.RUNWAY).orElseThrow();
		assertThat(year.severity()).isEqualTo(Severity.GOOD);
		assertThat(year.title()).isEqualTo("Your money has room");
		assertThat(year.text()).contains("more than a year");
	}

	@Test
	void whatIsOwedLeavesARunwayFindingThatWasNoPraiseAsItIs() {
		// under thirty days the finding never was good news: a debt changes nothing about it
		for (String bank : new String[] { "-150", "0", "50", "550", "1300", "2000", "2999.99" }) {
			Analysis.Insight without = insight(hundredADay(bank).analyse(PeriodCode.THIS_MONTH), InsightType.RUNWAY).orElseThrow();
			Analysis.Insight with = insight(hundredADay(bank).owes("Ravi", "9000").analyse(PeriodCode.THIS_MONTH),
					InsightType.RUNWAY)
				.orElseThrow();
			assertThat(with).as("bank %s", bank).isEqualTo(without);
		}
	}

	@Test
	void moreThanAYearOfMostlyBorrowedMoneyIsStatedButNotPraised() {
		Analysis analysis = hundredADay("900000").owes("Ravi", "899000").analyse(PeriodCode.THIS_MONTH);
		Analysis.Insight runway = insight(analysis, InsightType.RUNWAY).orElseThrow();
		assertThat(runway.severity()).isEqualTo(Severity.INFO);
		// not "about 365 days": the count stops at a year
		assertThat(runway.title()).isEqualTo("Money lasts more than a year");
		assertThat(runway.text()).isEqualTo("At about ₹100 a day, your ₹9,00,000 in bank and cash lasts more than a year, "
				+ "assuming no new money comes in.");
		assertThat(analysis.runway().daysLeft()).isEqualTo(365);
	}

	@Test
	void owingFriendsWithNoMoneyAtAllIsAWarningWithoutANegativeFigure() {
		for (String bank : new String[] { "0", "-150" }) {
			Analysis analysis = Ledger.on("2026-11-10")
				.everyDay("2026-11-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
				.balances(bank, "0", "0")
				.owes("Ravi", "300")
				.owes("Arun", "200")
				.analyse(PeriodCode.THIS_MONTH);
			Analysis.Insight borrowed = insight(analysis, InsightType.BORROWED).orElseThrow();
			assertThat(borrowed.severity()).isEqualTo(Severity.WARN);
			assertThat(borrowed.text()).isEqualTo("You owe friends ₹500 in total: Ravi ₹300, Arun ₹200. "
					+ "You have no money in bank or cash to pay it back.");
		}
	}

	@Test
	void noBorrowedInsightWhenNothingIsOwed() {
		assertThat(insight(studentWithMoney().analyse(PeriodCode.THIS_MONTH), InsightType.BORROWED)).isEmpty();
		// a friend who is paid back in full is no debt
		Analysis paidBack = studentWithMoney().owes("Ravi", "0").owes("Arun", "0.00").analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(paidBack, InsightType.BORROWED)).isEmpty();
		// and is not named next to one who is still owed money
		Analysis mixed = studentWithMoney().owes("Ravi", "0").owes("Arun", "75").analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(mixed, InsightType.BORROWED).orElseThrow().text()).startsWith("You owe Arun ₹75.");
	}

	@Test
	void owingMoneyChangesNoNumberOfTheAnalysis() {
		Analysis without = studentWithMoney().analyse(PeriodCode.THIS_MONTH);
		Analysis with = studentWithMoney().owes("Ravi", "1000").owes("Arun", "500").analyse(PeriodCode.THIS_MONTH);

		// borrowed money is not income and what is owed is not spending
		assertThat(with.totals()).isEqualTo(without.totals());
		assertThat(with.totals().spent()).isEqualTo("1000.00");
		assertThat(with.byCategory()).isEqualTo(without.byCategory());
		assertThat(with.byMode()).isEqualTo(without.byMode());
		assertThat(with.daily()).isEqualTo(without.daily());
		assertThat(with.topItems()).isEqualTo(without.topItems());
		assertThat(with.needsWants()).isEqualTo(without.needsWants());
		assertThat(with.runway()).isEqualTo(without.runway());
		assertThat(with.forecast()).isEqualTo(without.forecast());
		assertThat(with.health()).isEqualTo(without.health());
		assertThat(with.dataQuality()).isEqualTo(without.dataQuality());
		// the only difference is the one insight
		List<Analysis.Insight> others = with.insights().stream().filter(i -> i.type() != InsightType.BORROWED).toList();
		assertThat(without.insights()).containsAll(others);
	}

	@Test
	void theOrderOfTheDebtsDoesNotMatterAndEqualDebtsGoByName() {
		Ledger ledger = studentWithMoney().owes("bala", "300").owes("Chitra", "300").owes("Arun", "300").owes("Ravi", "900");
		String text = insight(ledger.analyse(PeriodCode.THIS_MONTH), InsightType.BORROWED).orElseThrow().text();
		assertThat(text).startsWith("You owe friends ₹1,800 in total: Ravi ₹900, Arun ₹300, bala ₹300 and 1 more.");
		for (long shuffle = 1; shuffle <= 5; shuffle++) {
			assertThat(insight(ledger.shuffled(shuffle).analyse(PeriodCode.THIS_MONTH), InsightType.BORROWED).orElseThrow()
				.text()).isEqualTo(text);
		}
	}

	@Test
	void aFriendsNameIsOnlyEverPlainTextInTheSentence() {
		// names that look like a slot of the template, like markup and like a rupee figure
		Analysis analysis = studentWithMoney().owes("{total}", "300").owes("<b>Ravi</b> & {left}", "1000")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(insight(analysis, InsightType.BORROWED).orElseThrow().text())
			.isEqualTo("You owe friends ₹1,300 in total: <b>Ravi</b> & {left} ₹1,000, {total} ₹300. "
					+ "After paying everyone back you would have ₹4,240 in bank and cash.");
		assertThat(analysis.totals().spent()).isEqualTo("1000.00");
	}

	@Test
	void everyRupeeFigureOfTheBorrowedInsightIsAComputedFact() {
		InsightEngine.Report report = studentWithMoney().owes("Ravi", "1000")
			.owes("Arun", "600.75")
			.owes("Chitra", "300")
			.owes("Divya", "100")
			.report(PeriodCode.THIS_MONTH);
		assertThat(report.rupeeFacts()).contains("₹2,000.75", "₹1,000", "₹600.75", "₹300", "₹3,539.25");
		// the fourth friend is counted, not named: her amount is not a figure of this sentence
		String text = insight(report.analysis(), InsightType.BORROWED).orElseThrow().text();
		assertThat(text).contains("and 1 more").doesNotContain("Divya").doesNotContain("₹100 ");
	}

	@Test
	void theBorrowedInsightIsAboutTodaySoLastMonthLeavesItOut() {
		Ledger ledger = Ledger.on("2026-11-10")
			.everyDay("2026-09-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("300", "0", "0")
			.owes("Ravi", "9000");
		assertThat(insight(ledger.analyse(PeriodCode.LAST_MONTH), InsightType.BORROWED)).isEmpty();
		for (PeriodCode code : List.of(PeriodCode.THIS_WEEK, PeriodCode.THIS_MONTH, PeriodCode.LAST_30_DAYS, PeriodCode.ALL_TIME)) {
			assertThat(insight(ledger.analyse(code), InsightType.BORROWED)).as(code.name()).isPresent();
		}
	}

	@Test
	void aUserWithNoSpendsYetStillSeesWhatIsOwed() {
		Analysis analysis = Ledger.on("2026-11-10").balances("1000", "0", "0").owes("Ravi", "1000").analyse(PeriodCode.THIS_MONTH);
		assertThat(analysis.totals().spent()).isEqualTo("0.00");
		assertThat(insight(analysis, InsightType.BORROWED).orElseThrow().text())
			.isEqualTo("You owe Ravi ₹1,000. After paying it back you would have ₹0 in bank and cash.");
	}

	// ------------------------------------------------------------------ the plain sums

	@Test
	void breakdownsAddUp() {
		LocalDate day = LocalDate.parse("2026-11-02");
		Analysis analysis = Ledger.on("2026-11-10")
			.spend(day, "300", Category.FOOD_MEALS, PaymentMode.UPI, "biryani", false)
			.spend(day, "100", Category.FOOD_MEALS, PaymentMode.CASH, "biryani", false)
			.spend(day.plusDays(1), "250", Category.SHOPPING, PaymentMode.CREDIT_CARD, "tshirt", false)
			.spend(day.plusDays(2), "150", Category.SNACKS_BEVERAGES, PaymentMode.UPI, "juice", false)
			.spend(day.plusDays(3), "120", Category.TRANSFERS_FEES, PaymentMode.UPI, "atm fee", false)
			.spend(day.plusDays(4), "80", Category.OTHER, PaymentMode.CASH, "xyzzy", true)
			.analyse(PeriodCode.THIS_MONTH);

		assertThat(analysis.totals().spent()).isEqualTo("1000.00");
		assertThat(analysis.totals().count()).isEqualTo(6);
		assertThat(analysis.period().days()).isEqualTo(10);
		assertThat(analysis.totals().avgPerDay()).isEqualTo("111.11"); // the first entry is on 2 Nov: nine tracked days

		assertThat(analysis.byCategory()).extracting(Analysis.CategoryShare::category)
			.containsExactly("FOOD_MEALS", "SHOPPING", "SNACKS_BEVERAGES", "TRANSFERS_FEES", "OTHER");
		Analysis.CategoryShare food = analysis.byCategory().get(0);
		assertThat(food.label()).isEqualTo("Food & Meals");
		assertThat(food.need()).isEqualTo("NEED");
		assertThat(food.amount()).isEqualTo("400.00");
		assertThat(food.count()).isEqualTo(2);
		assertThat(food.percent()).isEqualTo(40.0);
		assertThat(analysis.byCategory().stream().mapToDouble(Analysis.CategoryShare::percent).sum()).isEqualTo(100.0);

		assertThat(analysis.byMode()).extracting(Analysis.ModeShare::mode).containsExactly("UPI", "CREDIT_CARD", "CASH");
		assertThat(analysis.byMode().get(0).amount()).isEqualTo("570.00");
		assertThat(analysis.byMode().get(0).percent()).isEqualTo(57.0);
		assertThat(analysis.byMode().get(0).label()).isEqualTo("UPI");

		assertThat(analysis.daily()).hasSize(10);
		assertThat(analysis.daily().get(0)).isEqualTo(new Analysis.DayTotal("2026-11-01", "0.00"));
		assertThat(analysis.daily().get(1)).isEqualTo(new Analysis.DayTotal("2026-11-02", "400.00"));

		assertThat(analysis.topItems().get(0)).isEqualTo(new Analysis.TopItem("biryani", 2, "400.00", "FOOD_MEALS", "Food & Meals"));

		// transfers and unknown spends are outside the needs/wants split: 400 against 400
		assertThat(analysis.needsWants()).isEqualTo(new Analysis.NeedsWants("400.00", "400.00", 50.0));
		assertThat(analysis.dataQuality().needsReviewCount()).isEqualTo(1);
		assertThat(insight(analysis, InsightType.REVIEW_NEEDED).orElseThrow().text())
			.startsWith("1 spend this month (₹80) has an uncertain category.");
	}

	@Test
	void topItemsFallBackToTheDescriptionAndKeepEight() {
		Ledger ledger = Ledger.on("2026-11-10");
		LocalDate day = LocalDate.parse("2026-11-03");
		for (int i = 1; i <= 10; i++) {
			ledger.rows().add(new Expense(day, new BigDecimal(i * 10), Category.OTHER, PaymentMode.UPI, null, "  Thing " + i + " ", false));
		}
		Analysis analysis = ledger.analyse(PeriodCode.THIS_MONTH);
		assertThat(analysis.topItems()).hasSize(8);
		assertThat(analysis.topItems().get(0).item()).isEqualTo("thing 10");
		assertThat(analysis.topItems().get(7).item()).isEqualTo("thing 3");
	}

	@Test
	void allTimeDailySeriesIsCappedAtNinetyDays() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-05-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.analyse(PeriodCode.ALL_TIME);
		assertThat(analysis.period().from()).isEqualTo("2026-05-01");
		assertThat(analysis.period().days()).isEqualTo(194);
		assertThat(analysis.totals().spent()).isEqualTo("19400.00");
		assertThat(analysis.totals().previousSpent()).isNull();
		assertThat(analysis.daily()).hasSize(90);
		assertThat(analysis.daily().get(0).date()).isEqualTo("2026-08-13");
		assertThat(analysis.daily().get(89).date()).isEqualTo("2026-11-10");
	}

	// ------------------------------------------------------------------ forecast and health

	@Test
	void noForecastInTheFirstDaysOfTheMonth() {
		Analysis analysis = Ledger.on("2026-11-04")
			.everyDay("2026-10-01", "2026-11-04", "100", Category.FOOD_MEALS, "meals")
			.balances("3000", "0", "0")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(analysis.forecast()).isNull();
		assertThat(analysis.runway()).isNotNull();
	}

	@Test
	void steadySpendingForecastsAStraightLine() {
		Analysis analysis = Ledger.on("2026-11-15")
			.everyDay("2026-10-01", "2026-11-15", "100", Category.FOOD_MEALS, "meals")
			.balances("3000", "0", "0")
			.analyse(PeriodCode.THIS_MONTH);
		// 1,500 so far + 15 days x 100; the range is the minimum band of 10% of what is to come
		assertThat(analysis.forecast()).isEqualTo(new Analysis.Forecast("3000.00", "2850.00", "3150.00"));
		Analysis.Insight forecast = insight(analysis, InsightType.FORECAST).orElseThrow();
		assertThat(forecast.text()).isEqualTo(
				"At this pace you will have spent about ₹3,000 by 30 Nov, likely between ₹2,850 and ₹3,150. Last month you spent ₹3,100.");
	}

	@Test
	void healthScoreIsTheSumOfItsVisibleParts() {
		Analysis analysis = leakLedger().analyse(PeriodCode.THIS_MONTH);
		Analysis.Health health = analysis.health();
		assertThat(health.components()).extracting(Analysis.Component::name)
			.containsExactly("Runway", "Needs/wants", "Spikes", "Leaks", "Tracking"); // no Trend: one month of data
		assertThat(health.components().stream().mapToInt(Analysis.Component::max).sum()).isEqualTo(100);
		assertThat(health.components().stream().mapToInt(Analysis.Component::points).sum()).isEqualTo(health.score());
		assertThat(health.score()).isBetween(0, 100);
		assertThat(health.band()).isEqualTo(HealthScorer.band(health.score()));
		assertThat(health.provisional()).isFalse();
		assertThat(health.components()).allMatch(c -> c.points() >= 0 && c.points() <= c.max() && !c.note().isBlank());
	}

	@Test
	void healthIsProvisionalOnThinHistory() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-11-04", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("3000", "0", "0")
			.analyse(PeriodCode.THIS_MONTH);
		assertThat(analysis.health()).isNotNull();
		assertThat(analysis.health().provisional()).isTrue();
	}

	@Test
	void theWantsNoteNamesItsBase() {
		// last 30 days: needs ₹2,000, wants ₹1,000
		Ledger ledger = Ledger.on("2026-11-10")
			.everyDay("2026-10-22", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.everyDay("2026-11-01", "2026-11-10", "100", Category.SNACKS_BEVERAGES, "juice")
			.balances("9000", "0", "0");
		assertThat(needsWantsNote(ledger.analyse(PeriodCode.LAST_30_DAYS))).isEqualTo("Wants are 33% of your spending");

		// ₹3,000 lent to a friend is spending too, but outside the split: wants are 16.7% of all spending
		ledger.spend("2026-11-05", "3000", Category.TRANSFERS_FEES, "lent to friend");
		Analysis analysis = ledger.analyse(PeriodCode.LAST_30_DAYS);
		assertThat(needsWantsNote(analysis)).isEqualTo("Wants are 33% of your spending on needs and wants");
		Analysis.CategoryShare snacks = analysis.byCategory().stream().filter(c -> c.category().equals("SNACKS_BEVERAGES")).findFirst().orElseThrow();
		assertThat(snacks.percent()).isEqualTo(16.7);
	}

	private static String needsWantsNote(Analysis analysis) {
		return analysis.health().components().stream().filter(c -> c.name().equals("Needs/wants")).findFirst().orElseThrow().note();
	}

	@Test
	void healthBands() {
		assertThat(HealthScorer.band(100)).isEqualTo("Excellent");
		assertThat(HealthScorer.band(80)).isEqualTo("Excellent");
		assertThat(HealthScorer.band(79)).isEqualTo("Good");
		assertThat(HealthScorer.band(60)).isEqualTo("Good");
		assertThat(HealthScorer.band(59)).isEqualTo("Fair");
		assertThat(HealthScorer.band(40)).isEqualTo("Fair");
		assertThat(HealthScorer.band(39)).isEqualTo("Needs attention");
	}

	@Test
	void lastMonthLeavesOutTheTodayInsights() {
		Analysis analysis = Ledger.on("2026-11-10")
			.everyDay("2026-09-01", "2026-11-10", "100", Category.FOOD_MEALS, "meals")
			.balances("300", "0", "9000")
			.analyse(PeriodCode.LAST_MONTH);
		assertThat(analysis.period().from()).isEqualTo("2026-10-01");
		assertThat(analysis.totals().spent()).isEqualTo("3100.00");
		assertThat(analysis.insights()).noneMatch(i -> i.type() == InsightType.RUNWAY || i.type() == InsightType.CREDIT_CARD
				|| i.type() == InsightType.FORECAST);
		// the blocks about today are still there
		assertThat(analysis.runway()).isNotNull();
		assertThat(analysis.health()).isNotNull();
	}

}
