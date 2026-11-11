package com.spendanalyzer.ledger;

import static com.spendanalyzer.ledger.LedgerApi.adjustment;
import static com.spendanalyzer.ledger.LedgerApi.borrow;
import static com.spendanalyzer.ledger.LedgerApi.cardPayment;
import static com.spendanalyzer.ledger.LedgerApi.expense;
import static com.spendanalyzer.ledger.LedgerApi.income;
import static com.spendanalyzer.ledger.LedgerApi.repay;
import static com.spendanalyzer.ledger.LedgerApi.withdrawal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.spendanalyzer.ledger.excel.XlsxTestReader;
import com.spendanalyzer.ledger.excel.XlsxTestReader.Cell;
import com.spendanalyzer.ledger.excel.XlsxTestReader.SheetData;
import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /api/export/excel}: the real endpoint, the real database, the file read back. */
@IntegrationTest
class ExcelExportIntegrationTest {

	private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	EntryRequestParser parser;

	LedgerApi api;

	@BeforeEach
	void setUp() {
		api = new LedgerApi(mvc);
	}

	private MockHttpServletResponse download(AuthUser user) throws Exception {
		return mvc.perform(get("/api/export/excel").with(TestData.as(user)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();
	}

	private XlsxTestReader book(AuthUser user) throws Exception {
		return new XlsxTestReader(download(user).getContentAsByteArray());
	}

	@Test
	void theFileIsAnExcelDownloadThatIsNeverCached() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "100", "0", "0");
		MockHttpServletResponse response = download(user);
		assertThat(response.getContentType()).isEqualTo(XLSX);
		assertThat(response.getHeader("Content-Disposition"))
			.isEqualTo("attachment; filename=\"spend-analyzer-" + parser.today() + ".xlsx\"");
		assertThat(response.getHeader("Cache-Control")).contains("no-store");
		assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
		byte[] body = response.getContentAsByteArray();
		// a zip file starts with "PK"
		assertThat(body[0]).isEqualTo((byte) 'P');
		assertThat(body[1]).isEqualTo((byte) 'K');
		XlsxTestReader reader = new XlsxTestReader(body);
		assertThat(reader.sheetNames()).containsExactly("Summary", "Transactions", "Friends you owe");
		for (Map.Entry<String, byte[]> part : reader.parts().entrySet()) {
			assertThat(XlsxTestReader.parse(part.getValue())).as(part.getKey()).isNotNull();
		}
	}

	@Test
	void withoutLoginTheAnswerIs401AsJson() throws Exception {
		MockHttpServletResponse response = mvc.perform(get("/api/export/excel"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("unauthorized"))
			.andReturn()
			.getResponse();
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(response.getHeader("Content-Disposition")).isNull();
	}

	@Test
	void anErrorBeforeTheFileStartsIsTheUsualJsonError() throws Exception {
		AuthUser gone = TestData.createUser(jdbc);
		jdbc.sql("DELETE FROM users WHERE id = ?").param(gone.id()).update();
		MockHttpServletResponse response = mvc.perform(get("/api/export/excel").with(TestData.as(gone)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("not_found"))
			.andReturn()
			.getResponse();
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(response.getHeader("Content-Disposition")).isNull();
	}

	@Test
	void everyKindOfEntryReadsLikeTheTransactionList() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		api.onboard(user, "5000", "500", "1000");
		api.created(user, expense("lunch", "120", "UPI"));
		api.created(user, expense("=1+1", "10", "CASH"));
		api.created(user, expense("shoes", "200", "CREDIT_CARD"));
		api.created(user, income("2000", "BANK"));
		api.created(user, withdrawal("500"));
		api.created(user, cardPayment("300", "BANK"));
		// cash is 500 - 10 + 500 = 990 here; correcting it to 100 is a change of -890
		api.created(user, adjustment("CASH", "100"));
		api.created(user, borrow("Ravi", "1000", "BANK"));
		api.created(user, repay("ravi", "40", "CASH"));

		XlsxTestReader reader = book(user);
		SheetData sheet = reader.sheet("Transactions");
		assertThat(sheet.rows()).hasSize(1 + 12);
		Map<String, List<String>> byWhat = new LinkedHashMap<>();
		for (int row = 2; row <= sheet.rows().size(); row++) {
			List<String> values = ExcelExportTest.values(sheet, row);
			byWhat.put(values.get(2) + " / " + values.get(1), values.subList(3, values.size()));
			// every row has a real date and a time it was added
			assertThat(sheet.cells().get("A" + row).style()).isEqualTo(3);
			assertThat(sheet.cells().get("J" + row).style()).isEqualTo(4);
		}
		// the category is the AI's pick, so only its presence is checked
		assertThat(byWhat.get("lunch / Spend")).hasSizeGreaterThanOrEqualTo(3).contains("UPI", "-120.00");
		assertThat(byWhat.get("=1+1 / Spend")).contains("Cash", "-10.00");
		assertThat(byWhat.get("shoes / Spend")).contains("Credit card", "-200.00");
		assertThat(byWhat.get("Money added / Money added")).containsExactly("To bank", "2000.00");
		assertThat(byWhat.get("Cash withdrawn / Cash withdrawn")).containsExactly("Bank to cash", "-500.00");
		assertThat(byWhat.get("Card bill paid / Card bill paid")).containsExactly("From bank", "-300.00");
		assertThat(byWhat.get("Balance corrected / Balance corrected")).containsExactly("Cash in hand", "-890.00");
		assertThat(byWhat.get("Borrowed from Ravi / Borrowed")).containsExactly("UPI", "1000.00", "Ravi");
		// the friend keeps the spelling of the pay-back entry itself
		assertThat(byWhat.get("Paid back ravi / Paid back")).containsExactly("Cash", "-40.00", "ravi");
		// one opening entry per balance: bank, cash and the card due, each with its own sign
		List<List<String>> openings = sheet.rows()
			.stream()
			.skip(1)
			.filter(row -> "Opening balance".equals(row.get(1).value()))
			.map(row -> List.of(row.get(3).value(), row.get(4).value()))
			.toList();
		assertThat(openings).containsExactlyInAnyOrder(List.of("Bank balance", "5000.00"),
				List.of("Cash in hand", "500.00"), List.of("Credit card due", "1000.00"));
		// the user's "=1+1" is text, not a formula
		Cell trick = sheet.rows()
			.stream()
			.flatMap(List::stream)
			.filter(cell -> "=1+1".equals(cell.value()))
			.findFirst()
			.orElseThrow();
		assertThat(trick.type()).isEqualTo("inlineStr");
		assertThat(trick.formula()).isFalse();

		SheetData summary = reader.sheet("Summary");
		assertThat(summary.text("B4")).isEqualTo(user.name());
		assertThat(summary.text("B5")).isEqualTo(user.email());
		assertThat(summary.text("B6")).isEqualTo("+91 ••••••3210");
		// bank 5000 - 120 + 2000 - 500 - 300 + 1000; cash 100 - 40; card 1000 + 200 - 300
		assertThat(summary.text("B8")).isEqualTo("7080.00");
		assertThat(summary.text("B9")).isEqualTo("60.00");
		assertThat(summary.text("B10")).isEqualTo("900.00");
		assertThat(summary.text("B11")).isEqualTo("7140.00");
		assertThat(summary.text("B12")).isEqualTo("960.00");
		assertThat(summary.text("B14")).isEqualTo("12");
		assertThat(summary.cells()).doesNotContainKey("A15");

		SheetData friends = reader.sheet("Friends you owe");
		assertThat(ExcelExportTest.values(friends, 2)).containsExactly("Ravi", "960.00", "1000.00", "40.00",
				friends.text("E2"));
		assertThat(friends.rows().get(2)).isEmpty();
		assertThat(ExcelExportTest.values(friends, 4)).containsExactly("Total", "960.00", "1000.00", "40.00");
		assertThat(friends.xml()).contains("<autoFilter ref=\"A1:E2\"/>");
		assertThat(LedgerApi.text(mvc.perform(get("/api/export").with(TestData.as(user))))).contains("\"lunch\"");
	}

	@Test
	void theFileHoldsOnlyTheUsersOwnData() throws Exception {
		AuthUser alice = TestData.createUser(jdbc);
		AuthUser bob = TestData.createUser(jdbc);
		api.onboard(alice, "5000", "500", "0");
		api.created(alice, expense("alice lunch", "120", "CASH"));
		api.onboard(bob, "1", "2", "3");
		api.created(bob, expense("bob secret tea", "10", "CASH"));
		api.created(bob, borrow("Bobfriend", "5", "CASH"));

		String alices = allText(book(alice));
		assertThat(alices).contains("alice lunch").contains(alice.email());
		assertThat(alices).doesNotContain("bob secret tea").doesNotContain(bob.email()).doesNotContain("Bobfriend");
		assertThat(book(alice).sheet("Friends you owe").text("A1")).isEqualTo("You do not owe any friend money.");

		String bobs = allText(book(bob));
		assertThat(bobs).contains("bob secret tea").contains("Bobfriend").doesNotContain("alice");
	}

	@Test
	void onlyTheNewest20000EntriesAreIncludedAndTheSummarySaysSo() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		try {
			// one more than the cap; the oldest one has its own date and name
			jdbc.sql("""
					INSERT INTO entries (id, user_id, client_id, type, amount, account, payment_mode, description,
						category, entry_date)
					SELECT gen_random_uuid(), ?, gen_random_uuid(), 'EXPENSE', 1.00, 'CASH', 'CASH',
						CASE WHEN g = 20001 THEN 'the oldest' ELSE 'tea ' || g END, 'SNACKS_BEVERAGES',
						CASE WHEN g = 20001 THEN DATE '2001-01-01' ELSE DATE '2026-01-01' - (g / 100) END
					FROM generate_series(1, 20001) AS g
					""").param(user.id()).update();

			XlsxTestReader reader = book(user);
			SheetData sheet = reader.sheet("Transactions");
			assertThat(sheet.rows()).hasSize(1 + ExportService.MAX_ENTRIES);
			assertThat(sheet.xml()).doesNotContain("the oldest");
			assertThat(sheet.xml()).contains("<autoFilter ref=\"A1:J20001\"/>");
			SheetData summary = reader.sheet("Summary");
			assertThat(summary.text("B14")).isEqualTo("20000");
			assertThat(summary.text("A15")).isEqualTo("Only your newest 20,000 entries are included.");

			// the JSON backup keeps the same cap and the same shape
			String json = LedgerApi.text(mvc.perform(get("/api/export").with(TestData.as(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entries.length()").value(ExportService.MAX_ENTRIES)));
			assertThat(json).doesNotContain("the oldest");
		}
		finally {
			jdbc.sql("DELETE FROM users WHERE id = ?").param(user.id()).update();
		}
	}

	/** Every part of the file as one text, for "this name is nowhere in it" checks. */
	private static String allText(XlsxTestReader reader) {
		StringBuilder all = new StringBuilder();
		for (byte[] part : reader.parts().values()) {
			all.append(new String(part, StandardCharsets.UTF_8));
		}
		return all.toString();
	}

}
