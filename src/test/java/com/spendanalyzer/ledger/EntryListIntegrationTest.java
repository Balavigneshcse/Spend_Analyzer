package com.spendanalyzer.ledger;

import static com.spendanalyzer.ledger.LedgerApi.expense;
import static com.spendanalyzer.ledger.LedgerApi.income;
import static com.spendanalyzer.ledger.LedgerApi.read;
import static com.spendanalyzer.ledger.LedgerApi.withdrawal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/entries: filters, search, paging and order. */
@IntegrationTest
class EntryListIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	LedgerApi api;

	@BeforeEach
	void setUp() {
		api = new LedgerApi(mvc);
	}

	private String add(AuthUser user, Map<String, Object> request, String date, String category) throws Exception {
		request.put("date", date);
		if (category != null) {
			request.put("category", category);
		}
		return api.created(user, request);
	}

	private List<String> descriptions(AuthUser user, String query) throws Exception {
		return read(api.list(user, query).andExpect(status().isOk()), "$.items[*].description");
	}

	private List<String> ids(AuthUser user, String query) throws Exception {
		return read(api.list(user, query).andExpect(status().isOk()), "$.items[*].id");
	}

	@Test
	void emptyListForANewUser() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.list(user, "")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty())
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.hasMore").value(false))
			.andExpect(jsonPath("$.length()").value(5));
		mvc.perform(get("/api/entries")).andExpect(status().isUnauthorized());
	}

	@Test
	void newestFirstByDateThenByCreationTime() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		String oldest = add(user, expense("oldest", "1", "UPI"), "2026-01-01", null);
		String sameDayFirst = add(user, expense("same day, first", "1", "UPI"), "2026-02-01", null);
		String sameDaySecond = add(user, expense("same day, second", "1", "UPI"), "2026-02-01", null);
		String newest = add(user, expense("newest", "1", "UPI"), "2026-03-01", null);
		String backdated = add(user, expense("entered last, dated in between", "1", "UPI"), "2026-01-15", null);

		assertThat(ids(user, "")).containsExactly(newest, sameDaySecond, sameDayFirst, backdated, oldest);
	}

	@Test
	void filtersByDateTypeModeCategoryAndReviewFlag() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		add(user, expense("juice", "40", "UPI"), "2026-05-01", "SNACKS_BEVERAGES");
		add(user, expense("biryani", "120", "CASH"), "2026-05-02", "FOOD_MEALS");
		add(user, expense("pen", "10", "DEBIT_CARD"), "2026-05-03", "EDUCATION_STATIONERY");
		String shoes = add(user, expense("shoes", "800", "CREDIT_CARD"), "2026-05-04", "SHOPPING");
		add(user, income("500", "BANK"), "2026-05-05", null);
		add(user, withdrawal("200"), "2026-05-06", null);

		assertThat(descriptions(user, "?from=2026-05-02&to=2026-05-04")).containsExactly("shoes", "pen", "biryani");
		assertThat(descriptions(user, "?from=2026-05-04")).hasSize(3);
		assertThat(descriptions(user, "?to=2026-05-01")).containsExactly("juice");
		assertThat(descriptions(user, "?from=2026-05-03&to=2026-05-03")).containsExactly("pen");

		assertThat(descriptions(user, "?type=EXPENSE")).containsExactly("shoes", "pen", "biryani", "juice");
		api.list(user, "?type=INCOME").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].type").value("INCOME"));
		api.list(user, "?type=WITHDRAWAL").andExpect(jsonPath("$.total").value(1));
		api.list(user, "?type=CARD_PAYMENT").andExpect(jsonPath("$.total").value(0));

		assertThat(descriptions(user, "?mode=UPI")).containsExactly("juice");
		assertThat(descriptions(user, "?mode=CREDIT_CARD")).containsExactly("shoes");
		assertThat(descriptions(user, "?category=FOOD_MEALS")).containsExactly("biryani");
		assertThat(descriptions(user, "?type=EXPENSE&mode=CASH&category=FOOD_MEALS&from=2026-05-01&to=2026-05-31"))
			.containsExactly("biryani");
		assertThat(descriptions(user, "?mode=CASH&category=SHOPPING")).isEmpty();

		// the review flag is set by the AI; set it by hand here so the test does not depend on the engine
		jdbc.sql("UPDATE entries SET needs_review = (id = ?) WHERE user_id = ?")
			.params(UUID.fromString(shoes), user.id())
			.update();
		assertThat(descriptions(user, "?needsReview=true")).containsExactly("shoes");
		api.list(user, "?needsReview=false").andExpect(jsonPath("$.total").value(6));
	}

	@Test
	void searchIsCaseInsensitiveAndTreatsWildcardsAsText() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		add(user, expense("Bus Ticket to college", "20", "UPI"), "2026-06-01", null);
		add(user, expense("100% juice", "60", "UPI"), "2026-06-02", null);
		add(user, expense("1000 ml milk", "30", "UPI"), "2026-06-03", null);
		add(user, expense("snack_box", "45", "UPI"), "2026-06-04", null);
		add(user, expense("snackXbox", "45", "UPI"), "2026-06-05", null);
		add(user, expense("wow! shoes", "45", "UPI"), "2026-06-06", null);
		add(user, income("500", "BANK"), "2026-06-07", null); // no description: must never match or break

		assertThat(descriptions(user, "?q=bus ticket")).containsExactly("Bus Ticket to college");
		assertThat(descriptions(user, "?q=TICKET")).containsExactly("Bus Ticket to college");
		assertThat(descriptions(user, "?q=  college ")).containsExactly("Bus Ticket to college");
		// % and _ are plain characters, not wildcards
		assertThat(descriptions(user, "?q=100%")).containsExactly("100% juice");
		assertThat(descriptions(user, "?q=%")).containsExactly("100% juice");
		assertThat(descriptions(user, "?q=snack_box")).containsExactly("snack_box");
		assertThat(descriptions(user, "?q=_")).containsExactly("snack_box");
		assertThat(descriptions(user, "?q=!")).containsExactly("wow! shoes");
		assertThat(descriptions(user, "?q=nothing like this")).isEmpty();
		// an attempt to break out of the query is just text that matches nothing
		assertThat(descriptions(user, "?q=' OR '1'='1")).isEmpty();
		assertThat(descriptions(user, "?q=x'; DROP TABLE entries; --")).isEmpty();
		api.list(user, "").andExpect(jsonPath("$.total").value(7));
		// combined with another filter
		assertThat(descriptions(user, "?q=snack&from=2026-06-05")).containsExactly("snackXbox");
	}

	@Test
	void pagingReportsTotalAndHasMoreAndCapsTheSize() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		List<String> newestFirst = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			newestFirst.add(0, add(user, expense("spend " + i, "1", "CASH"), "2026-07-0" + (i + 1), null));
		}

		api.list(user, "?size=3")
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(3))
			.andExpect(jsonPath("$.total").value(7))
			.andExpect(jsonPath("$.hasMore").value(true));
		assertThat(ids(user, "?size=3&page=0")).containsExactlyElementsOf(newestFirst.subList(0, 3));
		assertThat(ids(user, "?size=3&page=1")).containsExactlyElementsOf(newestFirst.subList(3, 6));
		api.list(user, "?size=3&page=2")
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(newestFirst.get(6)))
			.andExpect(jsonPath("$.total").value(7))
			.andExpect(jsonPath("$.hasMore").value(false));
		api.list(user, "?size=3&page=50")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty())
			.andExpect(jsonPath("$.total").value(7))
			.andExpect(jsonPath("$.hasMore").value(false));
		api.list(user, "?size=7").andExpect(jsonPath("$.hasMore").value(false));

		// default 20, never more than 100 per page
		api.list(user, "").andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.items.length()").value(7));
		api.list(user, "?size=100000").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
		// total follows the filter
		api.list(user, "?from=2026-07-06&size=1")
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.hasMore").value(true));
	}

	@Test
	void badQueryValuesAreRefusedWithTheFieldName() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		String[][] cases = { { "?from=01-05-2026", "from" }, { "?to=2026-02-31", "to" },
				{ "?from=2026-05-10&to=2026-05-01", "to" }, { "?type=expense", "type" }, { "?mode=GPAY", "mode" },
				{ "?category=FOOD", "category" }, { "?needsReview=maybe", "needsReview" }, { "?page=-1", "page" },
				{ "?page=abc", "page" }, { "?size=0", "size" }, { "?size=-5", "size" }, { "?size=1.5", "size" },
				{ "?q=" + "x".repeat(121), "q" } };
		for (String[] testCase : cases) {
			api.list(user, testCase[0])
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("validation"))
				.andExpect(jsonPath("$.fields." + testCase[1]).isString());
		}
	}

}
