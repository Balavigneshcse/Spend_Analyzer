package com.spendanalyzer.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.spendanalyzer.insight.engine.PeriodWindow;
import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/analysis through the whole application, against the JSON shape in docs/API.md. */
@IntegrationTest
class AnalysisIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Clock clock;

	@Autowired
	@Qualifier("appZone")
	ZoneId appZone;

	// ------------------------------------------------------------------ test data, straight into the tables

	private void expense(AuthUser user, LocalDate date, String amount, String category, String mode, String item,
			String description, boolean needsReview) {
		String account = switch (mode) {
			case "CASH" -> "CASH";
			case "CREDIT_CARD" -> "CREDIT_CARD";
			default -> "BANK";
		};
		jdbc.sql("""
				INSERT INTO entries (id, user_id, client_id, type, amount, account, payment_mode, description,
				                     category, item, needs_review, entry_date)
				VALUES (?, ?, ?, 'EXPENSE', ?, ?, ?, ?, ?, ?, ?, ?)
				""")
			.params(UUID.randomUUID(), user.id(), UUID.randomUUID(), new BigDecimal(amount), account, mode, description,
					category, item, needsReview, date)
			.update();
	}

	private void otherEntry(AuthUser user, LocalDate date, String type, String amount, String account) {
		jdbc.sql("""
				INSERT INTO entries (id, user_id, client_id, type, amount, account, entry_date)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""").params(UUID.randomUUID(), user.id(), UUID.randomUUID(), type, new BigDecimal(amount), account, date).update();
	}

	private void account(AuthUser user, String type, String balance) {
		jdbc.sql("INSERT INTO accounts (id, user_id, type, balance) VALUES (?, ?, ?, ?)")
			.params(UUID.randomUUID(), user.id(), type, new BigDecimal(balance))
			.update();
	}

	private LocalDate today() {
		return PeriodWindow.today(clock, appZone);
	}

	/**
	 * Today: six juices, a lunch and one unclear spend (420 in 8 expenses).
	 * The 15th of last month: five meals and shoes (1,300 in 6).
	 * The 1st of the month before: five meals (500 in 5).
	 * Plus income, a withdrawal and a card payment, which are not spending.
	 */
	private AuthUser userWithLedger() {
		AuthUser user = TestData.createUser(jdbc);
		LocalDate today = today();
		LocalDate lastMonth15 = today.withDayOfMonth(1).minusMonths(1).withDayOfMonth(15);
		LocalDate monthBefore1 = today.withDayOfMonth(1).minusMonths(2);
		for (int i = 0; i < 6; i++) {
			expense(user, today, "40", "SNACKS_BEVERAGES", "UPI", "juice", "juse", false);
		}
		expense(user, today, "120", "FOOD_MEALS", "CASH", "lunch", "lunch", false);
		expense(user, today, "60", "OTHER", "UPI", "xyzzy", "xyzzy", true);
		for (int i = 0; i < 5; i++) {
			expense(user, lastMonth15, "100", "FOOD_MEALS", "UPI", "meals", "meals", false);
		}
		expense(user, lastMonth15, "800", "SHOPPING", "CREDIT_CARD", "shoes", "new shoes", false);
		for (int i = 0; i < 5; i++) {
			expense(user, monthBefore1, "100", "FOOD_MEALS", "UPI", "meals", "meals", false);
		}
		otherEntry(user, today, "INCOME", "5000", "BANK");
		otherEntry(user, today, "WITHDRAWAL", "700", "BANK");
		otherEntry(user, lastMonth15, "CARD_PAYMENT", "900", "BANK");
		account(user, "BANK", "3000");
		account(user, "CASH", "500");
		account(user, "CREDIT_CARD", "800");
		return user;
	}

	private String analysis(AuthUser user, String period) throws Exception {
		return mvc.perform(get("/api/analysis").param("period", period).with(TestData.as(user)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
	}

	private static Map<String, Object> object(String json, String path) {
		return JsonPath.read(json, path);
	}

	private static List<Map<String, Object>> list(String json, String path) {
		return JsonPath.read(json, path);
	}

	// ------------------------------------------------------------------ shape

	/** Checks every field name of API.md, for whatever the period holds. */
	private static void assertShape(String json, String period) {
		assertThat(json).doesNotContain("NaN").doesNotContain("Infinity").doesNotContain("-0.0");
		Map<String, Object> root = object(json, "$");
		assertThat(root.keySet()).containsOnly("period", "totals", "byCategory", "byMode", "daily", "topItems",
				"needsWants", "runway", "forecast", "health", "insights", "tips", "summary", "dataQuality", "generatedAt");

		Map<String, Object> periodBlock = object(json, "$.period");
		assertThat(periodBlock.keySet()).containsOnly("code", "label", "from", "to", "days");
		assertThat(periodBlock.get("code")).isEqualTo(period);
		assertThat((String) periodBlock.get("from")).matches("\\d{4}-\\d{2}-\\d{2}");
		assertThat((String) periodBlock.get("to")).matches("\\d{4}-\\d{2}-\\d{2}");
		assertThat(periodBlock.get("days")).isInstanceOf(Integer.class);

		Map<String, Object> totals = object(json, "$.totals");
		assertThat(totals.keySet()).containsOnly("spent", "count", "avgPerDay", "biggestDay", "biggestSpend",
				"previousSpent", "changePercent");
		assertMoney(totals.get("spent"));
		assertMoney(totals.get("avgPerDay"));
		assertThat(totals.get("count")).isInstanceOf(Integer.class);
		if (totals.get("biggestDay") != null) {
			assertThat(object(json, "$.totals.biggestDay").keySet()).containsOnly("date", "amount");
			assertThat(object(json, "$.totals.biggestSpend").keySet()).containsOnly("description", "amount", "date",
					"category", "categoryLabel");
		}
		if (totals.get("previousSpent") != null) {
			assertMoney(totals.get("previousSpent"));
		}
		if (totals.get("changePercent") != null) {
			assertThat(totals.get("changePercent")).isInstanceOf(Double.class);
		}

		// lists are arrays, never null
		for (String name : List.of("byCategory", "byMode", "daily", "topItems", "insights", "tips")) {
			assertThat(root.get(name)).as(name).isInstanceOf(List.class);
		}
		for (Map<String, Object> row : list(json, "$.byCategory")) {
			assertThat(row.keySet()).containsOnly("category", "label", "need", "amount", "count", "percent");
			assertMoney(row.get("amount"));
			assertThat(row.get("percent")).isInstanceOf(Double.class);
		}
		for (Map<String, Object> row : list(json, "$.byMode")) {
			assertThat(row.keySet()).containsOnly("mode", "label", "amount", "count", "percent");
			assertMoney(row.get("amount"));
		}
		List<Map<String, Object>> daily = list(json, "$.daily");
		assertThat(daily).hasSize(Math.min(90, (Integer) periodBlock.get("days")));
		for (Map<String, Object> row : daily) {
			assertThat(row.keySet()).containsOnly("date", "amount");
			assertMoney(row.get("amount"));
		}
		assertThat(daily.get(daily.size() - 1).get("date")).isEqualTo(periodBlock.get("to"));
		for (Map<String, Object> row : list(json, "$.topItems")) {
			assertThat(row.keySet()).containsOnly("item", "count", "amount", "category", "categoryLabel");
		}
		if (root.get("needsWants") != null) {
			assertThat(object(json, "$.needsWants").keySet()).containsOnly("needs", "wants", "wantsPercent");
		}
		if (root.get("runway") != null) {
			Map<String, Object> runway = object(json, "$.runway");
			assertThat(runway.keySet()).containsOnly("available", "dailyBurn", "daysLeft", "safePerDay", "daysToMonthEnd");
			assertMoney(runway.get("available"));
			assertMoney(runway.get("dailyBurn"));
			assertMoney(runway.get("safePerDay"));
			assertThat(runway.get("daysLeft")).isInstanceOf(Integer.class);
		}
		if (root.get("forecast") != null) {
			Map<String, Object> forecast = object(json, "$.forecast");
			assertThat(forecast.keySet()).containsOnly("monthEndExpected", "monthEndLow", "monthEndHigh");
			forecast.values().forEach(AnalysisIntegrationTest::assertMoney);
		}
		if (root.get("health") != null) {
			Map<String, Object> health = object(json, "$.health");
			assertThat(health.keySet()).containsOnly("score", "band", "provisional", "components");
			assertThat(health.get("score")).isInstanceOf(Integer.class);
			assertThat(health.get("provisional")).isInstanceOf(Boolean.class);
			assertThat((String) health.get("band")).isIn("Excellent", "Good", "Fair", "Needs attention");
			List<Map<String, Object>> components = list(json, "$.health.components");
			assertThat(components).isNotEmpty();
			for (Map<String, Object> component : components) {
				assertThat(component.keySet()).containsOnly("name", "points", "max", "note");
			}
		}
		List<Map<String, Object>> insights = list(json, "$.insights");
		assertThat(insights.size()).isLessThanOrEqualTo(6);
		for (Map<String, Object> insight : insights) {
			assertThat(insight.keySet()).containsOnly("id", "type", "severity", "title", "text");
			assertThat((String) insight.get("severity")).isIn("ALERT", "WARN", "INFO", "GOOD");
			assertThat((String) insight.get("type")).isIn("SMALL_LEAK", "UNUSUAL_SPEND", "PERIOD_CHANGE", "RUNWAY",
					"FORECAST", "RECURRING", "WEEK_PATTERN", "NEEDS_WANTS", "TOP_CATEGORY", "REVIEW_NEEDED", "CREDIT_CARD", "BORROWED",
					"POSITIVE");
		}
		List<Map<String, Object>> tips = list(json, "$.tips");
		assertThat(tips.size()).isLessThanOrEqualTo(3);
		for (Map<String, Object> tip : tips) {
			assertThat(tip.keySet()).containsOnly("text", "saving");
		}
		assertThat((String) root.get("summary")).isNotBlank();
		Map<String, Object> quality = object(json, "$.dataQuality");
		assertThat(quality.keySet()).containsOnly("needsReviewCount", "enoughData", "message");
		assertThat(quality.get("enoughData")).isInstanceOf(Boolean.class);
		assertThat((String) root.get("generatedAt")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
	}

	private static void assertMoney(Object value) {
		assertThat(value).isInstanceOf(String.class);
		assertThat((String) value).matches("-?\\d+\\.\\d{2}");
	}

	// ------------------------------------------------------------------ tests

	@Test
	void everyPeriodHasTheShapeOfTheContract() throws Exception {
		AuthUser user = userWithLedger();
		for (String period : List.of("THIS_WEEK", "THIS_MONTH", "LAST_MONTH", "LAST_30_DAYS", "ALL_TIME")) {
			assertShape(analysis(user, period), period);
		}
	}

	@Test
	void thisMonthCountsOnlyThisMonthsExpenses() throws Exception {
		AuthUser user = userWithLedger();
		LocalDate today = today();
		String json = analysis(user, "THIS_MONTH");

		assertThat(object(json, "$.period")).containsEntry("label", "This month")
			.containsEntry("from", today.withDayOfMonth(1).toString())
			.containsEntry("to", today.toString())
			.containsEntry("days", today.getDayOfMonth());
		// income, withdrawal and card payment are not spending
		assertThat(object(json, "$.totals")).containsEntry("spent", "420.00").containsEntry("count", 8);
		assertThat(object(json, "$.totals.biggestDay")).containsEntry("date", today.toString()).containsEntry("amount", "420.00");
		assertThat(object(json, "$.totals.biggestSpend")).containsEntry("description", "lunch")
			.containsEntry("amount", "120.00")
			.containsEntry("category", "FOOD_MEALS")
			.containsEntry("categoryLabel", "Food & Meals");

		List<Map<String, Object>> categories = list(json, "$.byCategory");
		assertThat(categories).extracting(c -> c.get("category")).containsExactly("SNACKS_BEVERAGES", "FOOD_MEALS", "OTHER");
		assertThat(categories.get(0)).containsEntry("label", "Snacks & Beverages")
			.containsEntry("need", "WANT")
			.containsEntry("amount", "240.00")
			.containsEntry("count", 6)
			.containsEntry("percent", 57.1);
		List<Map<String, Object>> modes = list(json, "$.byMode");
		assertThat(modes).extracting(m -> m.get("mode")).containsExactly("UPI", "CASH");
		assertThat(modes.get(0)).containsEntry("label", "UPI").containsEntry("amount", "300.00").containsEntry("percent", 71.4);

		// repeated buys are grouped by the item the AI recognised, not by what was typed
		assertThat(list(json, "$.topItems").get(0)).containsEntry("item", "juice")
			.containsEntry("count", 6)
			.containsEntry("amount", "240.00")
			.containsEntry("category", "SNACKS_BEVERAGES");
		assertThat(object(json, "$.needsWants")).containsEntry("needs", "120.00")
			.containsEntry("wants", "240.00")
			.containsEntry("wantsPercent", 66.7);
		assertThat(object(json, "$.dataQuality")).containsEntry("needsReviewCount", 1).containsEntry("enoughData", true);

		List<Map<String, Object>> insights = list(json, "$.insights");
		assertThat(insights).anySatisfy(insight -> {
			assertThat(insight).containsEntry("id", "leak-juice").containsEntry("type", "SMALL_LEAK");
			assertThat((String) insight.get("text")).contains("6 times").contains("₹240");
		});
		assertThat((String) JsonPath.read(json, "$.summary")).contains("₹420").contains("8 purchases");

		// This week holds the same eight expenses whatever today's date is, so its insights are fixed.
		String week = analysis(user, "THIS_WEEK");
		assertThat(object(week, "$.totals")).containsEntry("spent", "420.00").containsEntry("count", 8);
		List<Map<String, Object>> weekInsights = list(week, "$.insights");
		assertThat(weekInsights).extracting(insight -> insight.get("type"))
			.containsExactlyInAnyOrder("SMALL_LEAK", "NEEDS_WANTS", "TOP_CATEGORY", "REVIEW_NEEDED", "CREDIT_CARD");
		assertThat(weekInsights).anySatisfy(insight -> {
			assertThat(insight).containsEntry("type", "CREDIT_CARD").containsEntry("severity", "INFO");
			assertThat((String) insight.get("text")).contains("₹800").contains("₹3,500");
		});
		assertThat(list(week, "$.tips")).isNotEmpty();

		// one spending day in the last 30 (two at most): not enough for a pace, so these are null, not missing
		Map<String, Object> root = object(json, "$");
		assertThat(root).containsKey("runway").containsKey("forecast");
		assertThat(root.get("runway")).isNull();
		assertThat(root.get("forecast")).isNull();
		assertThat(root.get("health")).isNotNull();
	}

	@Test
	void lastMonthComparesWithTheMonthBefore() throws Exception {
		AuthUser user = userWithLedger();
		LocalDate first = today().withDayOfMonth(1).minusMonths(1);
		String json = analysis(user, "LAST_MONTH");

		assertThat(object(json, "$.period")).containsEntry("from", first.toString())
			.containsEntry("to", first.withDayOfMonth(first.lengthOfMonth()).toString())
			.containsEntry("days", first.lengthOfMonth());
		assertThat(object(json, "$.totals")).containsEntry("spent", "1300.00")
			.containsEntry("count", 6)
			.containsEntry("previousSpent", "500.00")
			.containsEntry("changePercent", 160.0);
		assertThat(object(json, "$.totals.biggestSpend")).containsEntry("description", "new shoes").containsEntry("amount", "800.00");
		assertThat(list(json, "$.byMode")).extracting(m -> m.get("mode")).containsExactly("CREDIT_CARD", "UPI");
	}

	@Test
	void allTimeStartsAtTheFirstExpenseAndHasNoComparison() throws Exception {
		AuthUser user = userWithLedger();
		String json = analysis(user, "ALL_TIME");

		assertThat(object(json, "$.period")).containsEntry("from", today().withDayOfMonth(1).minusMonths(2).toString())
			.containsEntry("label", "All time");
		Map<String, Object> totals = object(json, "$.totals");
		assertThat(totals).containsEntry("spent", "2220.00").containsEntry("count", 19);
		assertThat(totals).containsKey("previousSpent").containsKey("changePercent");
		assertThat(totals.get("previousSpent")).isNull();
		assertThat(totals.get("changePercent")).isNull();
	}

	@Test
	void runwayForecastAndHealthAppearWithEnoughRecentData() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		LocalDate today = today();
		for (int i = 0; i < 12; i++) {
			expense(user, today, "50", "FOOD_MEALS", "UPI", "meals", "meals", false);
		}
		expense(user, today.minusDays(1), "600", "FOOD_MEALS", "UPI", "meals", "meals", false);
		expense(user, today.minusDays(2), "600", "FOOD_MEALS", "CASH", "meals", "meals", false);
		account(user, "BANK", "6000"); // no cash and no card row: they count as zero

		String json = analysis(user, "LAST_30_DAYS");
		assertShape(json, "LAST_30_DAYS");
		// three days of 600 each: 600 a day, so 6,000 lasts ten days
		assertThat(object(json, "$.runway")).containsEntry("available", "6000.00")
			.containsEntry("dailyBurn", "600.00")
			.containsEntry("daysLeft", 10)
			.containsEntry("daysToMonthEnd", today.lengthOfMonth() - today.getDayOfMonth());
		assertThat(object(json, "$").get("forecast")).isNotNull();
		assertThat(object(json, "$.health")).containsEntry("provisional", true);
		assertThat(list(json, "$.health.components")).extracting(c -> c.get("name")).contains("Runway", "Tracking");
	}

	@Test
	void aNewUserGetsAValidEmptyAnalysis() throws Exception {
		AuthUser user = TestData.createUser(jdbc); // no accounts, no entries
		for (String period : List.of("THIS_WEEK", "THIS_MONTH", "LAST_MONTH", "LAST_30_DAYS", "ALL_TIME")) {
			String json = analysis(user, period);
			assertShape(json, period);
			assertThat(object(json, "$.totals")).containsEntry("spent", "0.00").containsEntry("count", 0);
			Map<String, Object> root = object(json, "$");
			for (String block : List.of("needsWants", "runway", "forecast", "health")) {
				assertThat(root).containsKey(block);
				assertThat(root.get(block)).as(block).isNull();
			}
			for (String name : List.of("byCategory", "byMode", "topItems", "insights", "tips")) {
				assertThat((List<?>) root.get(name)).as(name).isEmpty();
			}
			assertThat(object(json, "$.dataQuality")).containsEntry("enoughData", false);
			assertThat(object(json, "$.dataQuality").get("message")).isNotNull();
		}
	}

	@Test
	void periodDefaultsToThisMonth() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		mvc.perform(get("/api/analysis").with(TestData.as(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.period.code").value("THIS_MONTH"));
	}

	@Test
	void needsLogin() throws Exception {
		mvc.perform(get("/api/analysis").param("period", "THIS_MONTH"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("unauthorized"));
	}

	@Test
	void invalidPeriodIsAValidationError() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		mvc.perform(get("/api/analysis").param("period", "YESTERDAY").with(TestData.as(user)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation"))
			.andExpect(jsonPath("$.fields.period").isNotEmpty())
			.andExpect(jsonPath("$.message").isNotEmpty());
	}

	@Test
	void anotherUsersDataIsNeverIncluded() throws Exception {
		AuthUser owner = userWithLedger();
		AuthUser stranger = TestData.createUser(jdbc);
		expense(stranger, today(), "9999", "SHOPPING", "UPI", "laptop", "laptop", false);
		account(stranger, "BANK", "123456");

		String ownersView = analysis(owner, "ALL_TIME");
		assertThat(object(ownersView, "$.totals")).containsEntry("spent", "2220.00").containsEntry("count", 19);
		assertThat(ownersView).doesNotContain("laptop").doesNotContain("9999").doesNotContain("123456")
			.doesNotContain("1,23,456");

		String strangersView = analysis(stranger, "ALL_TIME");
		assertThat(object(strangersView, "$.totals")).containsEntry("spent", "9999.00").containsEntry("count", 1);
		assertThat(strangersView).doesNotContain("juice").doesNotContain("shoes");
	}

	// ------------------------------------------------------------------ borrowing from friends

	private void debtEntry(AuthUser user, LocalDate date, String type, String person, String amount, String account) {
		jdbc.sql("""
				INSERT INTO entries (id, user_id, client_id, type, amount, account, person, person_key, entry_date)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")
			.params(UUID.randomUUID(), user.id(), UUID.randomUUID(), type, new BigDecimal(amount), account, person,
					person.toLowerCase(Locale.ROOT), date)
			.update();
	}

	/** Every block of numbers in the answer; the words (insights, tips, summary) are left out. */
	private static Map<String, Object> numbers(String json) {
		Map<String, Object> blocks = new LinkedHashMap<>();
		for (String block : List.of("period", "totals", "byCategory", "byMode", "daily", "topItems", "needsWants", "runway",
				"forecast", "health", "dataQuality")) {
			blocks.put(block, JsonPath.read(json, "$." + block));
		}
		return blocks;
	}

	@Test
	void borrowingAndPayingBackAreNeitherSpendingNorIncome() throws Exception {
		AuthUser user = userWithLedger();
		List<String> periods = List.of("THIS_WEEK", "THIS_MONTH", "LAST_MONTH", "LAST_30_DAYS", "ALL_TIME");
		Map<String, Map<String, Object>> before = new LinkedHashMap<>();
		for (String period : periods) {
			before.put(period, numbers(analysis(user, period)));
		}

		LocalDate today = today();
		LocalDate lastMonth15 = today.withDayOfMonth(1).minusMonths(1).withDayOfMonth(15);
		debtEntry(user, today, "BORROW", "Ravi", "1000", "BANK");
		debtEntry(user, lastMonth15, "BORROW", "ravi", "300", "CASH");
		debtEntry(user, today, "REPAY", "RAVI", "500", "BANK");
		debtEntry(user, today.withDayOfMonth(1).minusMonths(2), "BORROW", "Arun", "500", "CASH");
		debtEntry(user, lastMonth15, "BORROW", "Kumar", "200", "BANK");
		debtEntry(user, today, "REPAY", "Kumar", "200", "CASH");

		for (String period : periods) {
			String json = analysis(user, period);
			assertShape(json, period);
			// not a rupee of it counts as spending, in any period, in any block
			assertThat(numbers(json)).as(period).isEqualTo(before.get(period));
			// the friend who is paid back in full is not mentioned
			assertThat(json).doesNotContain("Kumar");
		}
		assertThat(object(analysis(user, "THIS_MONTH"), "$.totals")).containsEntry("spent", "420.00").containsEntry("count", 8);
		assertThat(object(analysis(user, "ALL_TIME"), "$.totals")).containsEntry("spent", "2220.00").containsEntry("count", 19);

		// what is owed shows up as one insight about today: bank 3,000 + cash 500 against 800 + 500
		List<Map<String, Object>> week = list(analysis(user, "THIS_WEEK"), "$.insights");
		assertThat(week).extracting(insight -> insight.get("type"))
			.containsExactlyInAnyOrder("SMALL_LEAK", "NEEDS_WANTS", "TOP_CATEGORY", "REVIEW_NEEDED", "CREDIT_CARD", "BORROWED");
		assertThat(week).filteredOn(insight -> "BORROWED".equals(insight.get("type"))).singleElement().satisfies(insight -> {
			assertThat(insight).containsEntry("id", "borrowed")
				.containsEntry("severity", "INFO")
				.containsEntry("title", "Money owed to friends")
				.containsEntry("text", "You owe friends ₹1,300 in total: Ravi ₹800, Arun ₹500. "
						+ "After paying everyone back you would have ₹2,200 in bank and cash.");
		});
		// last month is about last month, not about today
		assertThat(list(analysis(user, "LAST_MONTH"), "$.insights")).extracting(insight -> insight.get("type"))
			.doesNotContain("BORROWED", "CREDIT_CARD", "RUNWAY");
	}

	@Test
	void aDebtLargerThanBankAndCashIsAWarning() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		account(user, "BANK", "100");
		account(user, "CASH", "250.50");
		debtEntry(user, today(), "BORROW", "Ravi <b>K</b>", "1000", "BANK");

		String json = analysis(user, "THIS_MONTH");
		assertShape(json, "THIS_MONTH");
		assertThat(object(json, "$.totals")).containsEntry("spent", "0.00").containsEntry("count", 0);
		assertThat(list(json, "$.insights")).singleElement().satisfies(insight -> {
			assertThat(insight).containsEntry("type", "BORROWED").containsEntry("severity", "WARN");
			// the name is the user's own text, passed on as it is: the page shows it with textContent
			assertThat(insight).containsEntry("text",
					"You owe Ravi <b>K</b> ₹1,000. That is more than the ₹350.50 you have in bank and cash.");
		});

		// paid back: nothing left to say
		debtEntry(user, today(), "REPAY", "ravi <b>k</b>", "1000", "BANK");
		assertThat(list(analysis(user, "THIS_MONTH"), "$.insights")).isEmpty();
	}

	@Test
	void anotherUsersDebtsAreNeverIncluded() throws Exception {
		AuthUser owner = userWithLedger();
		AuthUser stranger = userWithLedger();
		debtEntry(stranger, today(), "BORROW", "Zubeida", "7777", "BANK");

		String ownersView = analysis(owner, "THIS_WEEK");
		assertThat(ownersView).doesNotContain("Zubeida").doesNotContain("7,777").doesNotContain("BORROWED");
		assertThat(analysis(stranger, "THIS_WEEK")).contains("Zubeida").contains("₹7,777");
	}

}
