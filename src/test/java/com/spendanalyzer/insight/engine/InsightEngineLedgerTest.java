package com.spendanalyzer.insight.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
		assertThat(analysis.summary()).startsWith("So far there are 4 purchases this month: ₹400 in total, about ₹40 a day.")
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
		assertThat(analysis.totals().avgPerDay()).isEqualTo("100.00");

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
