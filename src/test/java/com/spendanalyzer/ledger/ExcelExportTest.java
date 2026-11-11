package com.spendanalyzer.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.spendanalyzer.ledger.excel.XlsxTestReader;
import com.spendanalyzer.ledger.excel.XlsxTestReader.Cell;
import com.spendanalyzer.ledger.excel.XlsxTestReader.SheetData;
import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.EntryType;
import com.spendanalyzer.model.PaymentMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The layout of the Excel download, from data made in memory (no database). */
class ExcelExportTest {

	static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

	static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

	/** 11 Nov 2026, 18:00 in India. */
	static final Instant NOW = Instant.parse("2026-11-11T12:30:00Z");

	static final String LONG_TEXT = "Long note ".repeat(20);

	private final ExcelExport excel = new ExcelExport(INDIA);

	private static int created;

	static EntryRow row(EntryType type, String amount, AccountType account, PaymentMode mode, String description,
			Category category, String person, boolean needsReview, LocalDate date) {
		// every row a minute older than the one before, like the newest-first list
		Instant at = NOW.minusSeconds(60L * ++created);
		return new EntryRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), type, new BigDecimal(amount),
				account, person, person == null ? null : person.toLowerCase(), mode, description, category, null, null,
				null, null, needsReview, false, date, at, at);
	}

	static EntryRow spend(String description, String amount, PaymentMode mode, LocalDate date) {
		return row(EntryType.EXPENSE, amount, mode.account(), mode, description, Category.FOOD_MEALS, null, false, date);
	}

	/** Every kind of entry, the tricky texts, and {@code extraSpends} plain spends at the end. */
	static ExportService.Snapshot fixture(int extraSpends, boolean cut) {
		List<EntryRow> rows = new ArrayList<>();
		rows.add(row(EntryType.EXPENSE, "120.00", AccountType.BANK, PaymentMode.UPI, "Lunch at canteen",
				Category.FOOD_MEALS, null, true, TODAY));
		rows.add(spend("=1+1", "10.00", PaymentMode.CASH, TODAY));
		rows.add(spend("@SUM(A1)", "11.00", PaymentMode.CASH, TODAY));
		rows.add(spend("<b>&amp;</b>", "12.00", PaymentMode.DEBIT_CARD, TODAY));
		rows.add(spend("Chai ☕ with friends 😀", "15.00", PaymentMode.CASH, TODAY));
		rows.add(spend("தேநீர் கடை", "20.00", PaymentMode.CASH, TODAY));
		rows.add(spend(LONG_TEXT, "30.00", PaymentMode.UPI, TODAY));
		rows.add(spend("+1+1", "1.00", PaymentMode.CASH, TODAY));
		rows.add(spend("=HYPERLINK(\"http://example.com\",\"click\")", "2.00", PaymentMode.CASH, TODAY));
		rows.add(row(EntryType.EXPENSE, "999.00", AccountType.CREDIT_CARD, PaymentMode.CREDIT_CARD, "Shoes",
				Category.SHOPPING, null, false, TODAY.minusDays(1)));
		rows.add(row(EntryType.REPAY, "400.00", AccountType.CASH, null, null, null, "Ravi", false, TODAY.minusDays(1)));
		rows.add(row(EntryType.BORROW, "1000.00", AccountType.BANK, null, "for books", null, "Ravi", false,
				TODAY.minusDays(2)));
		rows.add(row(EntryType.CARD_PAYMENT, "1500.00", AccountType.BANK, null, null, null, null, false,
				TODAY.minusDays(3)));
		rows.add(row(EntryType.WITHDRAWAL, "500.00", AccountType.BANK, null, "ATM", null, null, false,
				TODAY.minusDays(3)));
		rows.add(row(EntryType.INCOME, "2000.00", AccountType.BANK, null, "Pocket money", null, null, false,
				TODAY.minusDays(4)));
		rows.add(row(EntryType.ADJUSTMENT, "-120.50", AccountType.CASH, null, "Recounted", null, null, false,
				TODAY.minusDays(5)));
		rows.add(row(EntryType.ADJUSTMENT, "500.00", AccountType.CASH, null, "Opening balance", null, null, false,
				TODAY.minusDays(30)));
		rows.add(row(EntryType.ADJUSTMENT, "5000.00", AccountType.BANK, null, "Opening balance", null, null, false,
				TODAY.minusDays(30)));
		for (int i = 0; i < extraSpends; i++) {
			rows.add(spend("Snack " + (i + 1), "25.50", PaymentMode.UPI, TODAY.minusDays(31 + i / 10)));
		}
		BalancesView balances = new BalancesView("-250.00", "380.00", "1200.00", "130.00", "850.00", true);
		DebtsView debts = new DebtsView("850.00",
				List.of(new DebtsView.Person("Ravi", "600.00", "1000.00", "400.00", TODAY.minusDays(1).toString()),
						new DebtsView.Person("Priya & <Co>", "250.00", "250.00", "0.00", TODAY.minusDays(7).toString())),
				List.of("Priya & <Co>", "Ravi"));
		return new ExportService.Snapshot(NOW, new ExportService.UserView("Bala", "bala@example.com", "+91 ••••••3210"),
				balances, debts, rows, cut);
	}

	private XlsxTestReader written(ExportService.Snapshot data) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		excel.write(data, out);
		return new XlsxTestReader(out.toByteArray());
	}

	@Test
	void theBookHasThreeSheetsInOrder() throws Exception {
		assertThat(written(fixture(0, false)).sheetNames()).containsExactly("Summary", "Transactions", "Friends you owe");
	}

	@Test
	void theSummaryHasTheUserAndTheBalances() throws Exception {
		SheetData summary = written(fixture(3, false)).sheet("Summary");
		assertThat(summary.text("A1")).isEqualTo("Your Spend Analyzer data");
		assertThat(summary.text("A3")).isEqualTo("Exported on");
		// 18:00 in India on 11 Nov 2026
		assertThat(new BigDecimal(summary.text("B3"))).isEqualByComparingTo("46337.75");
		assertThat(summary.text("B4")).isEqualTo("Bala");
		assertThat(summary.text("B5")).isEqualTo("bala@example.com");
		assertThat(summary.text("B6")).isEqualTo("+91 ••••••3210");
		assertThat(summary.text("A8")).isEqualTo("Bank balance");
		assertThat(summary.cells().get("B8")).isEqualTo(new Cell("B8", null, 2, "-250.00", false));
		assertThat(summary.text("A9")).isEqualTo("Cash in hand");
		assertThat(summary.text("B9")).isEqualTo("380.00");
		assertThat(summary.text("A10")).isEqualTo("Credit card due");
		assertThat(summary.text("B10")).isEqualTo("1200.00");
		assertThat(summary.text("A11")).isEqualTo("Total money you have");
		assertThat(summary.text("B11")).isEqualTo("130.00");
		assertThat(summary.text("A12")).isEqualTo("Owed to friends");
		assertThat(summary.text("B12")).isEqualTo("850.00");
		assertThat(summary.text("A14")).isEqualTo("Entries in this file");
		assertThat(summary.text("B14")).isEqualTo("21");
		assertThat(summary.cells()).doesNotContainKey("A15");
	}

	@Test
	void theSummarySaysWhenTheCapCutTheList() throws Exception {
		SheetData summary = written(fixture(0, true)).sheet("Summary");
		assertThat(summary.text("A15")).isEqualTo("Only your newest 20,000 entries are included.");
	}

	@Test
	void transactionsUseThePagesWordsAndSigns() throws Exception {
		SheetData sheet = written(fixture(2, false)).sheet("Transactions");
		assertThat(sheet.rows().getFirst()).extracting(Cell::value)
			.containsExactly("Date", "Type", "What", "Category", "Paid by / Account", "Amount", "Friend", "Note",
					"Needs checking", "Added on");
		assertThat(sheet.rows()).hasSize(1 + 18 + 2);

		// row 2: a UPI spend that needs a check
		assertThat(sheet.text("A2")).isEqualTo("46337");
		assertThat(sheet.cells().get("A2").style()).isEqualTo(3);
		assertThat(values(sheet, 2)).containsExactly("46337", "Spend", "Lunch at canteen", "Food & Meals", "UPI",
				"-120.00", "Yes");
		assertThat(sheet.cells().get("F2").style()).isEqualTo(2);
		assertThat(sheet.cells().get("J2").style()).isEqualTo(4);
		// the tricky texts come back exactly, as text
		assertThat(sheet.text("C3")).isEqualTo("=1+1");
		assertThat(sheet.cells().get("C3").formula()).isFalse();
		assertThat(sheet.text("C4")).isEqualTo("@SUM(A1)");
		assertThat(sheet.text("C5")).isEqualTo("<b>&amp;</b>");
		assertThat(sheet.text("E5")).isEqualTo("Debit card");
		assertThat(sheet.text("C6")).isEqualTo("Chai ☕ with friends 😀");
		assertThat(sheet.text("C7")).isEqualTo("தேநீர் கடை");
		assertThat(sheet.text("C8")).isEqualTo(LONG_TEXT);
		assertThat(sheet.text("C11")).isEqualTo("Shoes");
		assertThat(sheet.text("E11")).isEqualTo("Credit card");
		assertThat(sheet.text("F11")).isEqualTo("-999.00");
		// money with a friend: the friend in What and Friend, the description as the note
		assertThat(values(sheet, 12)).containsExactly("46336", "Paid back", "Paid back Ravi", "Cash", "-400.00", "Ravi");
		assertThat(values(sheet, 13)).containsExactly("46335", "Borrowed", "Borrowed from Ravi", "UPI", "1000.00", "Ravi",
				"for books");
		assertThat(sheet.cells()).doesNotContainKey("D13");
		assertThat(values(sheet, 14)).containsExactly("46334", "Card bill paid", "Card bill paid", "From bank",
				"-1500.00");
		assertThat(values(sheet, 15)).containsExactly("46334", "Cash withdrawn", "ATM", "Bank to cash", "-500.00");
		assertThat(values(sheet, 16)).containsExactly("46333", "Money added", "Pocket money", "To bank", "2000.00");
		assertThat(values(sheet, 17)).containsExactly("46332", "Balance corrected", "Recounted", "Cash in hand",
				"-120.50");
		assertThat(values(sheet, 18)).containsExactly("46307", "Opening balance", "Opening balance", "Cash in hand",
				"500.00");
		assertThat(values(sheet, 19)).containsExactly("46307", "Opening balance", "Opening balance", "Bank balance",
				"5000.00");
		assertThat(sheet.text("C21")).isEqualTo("Snack 2");
		assertThat(sheet.xml()).contains("<autoFilter ref=\"A1:J21\"/>").contains("state=\"frozen\"");
	}

	@Test
	void friendsYouOweHaveATotalRow() throws Exception {
		SheetData sheet = written(fixture(0, false)).sheet("Friends you owe");
		assertThat(sheet.rows().getFirst()).extracting(Cell::value)
			.containsExactly("Friend", "Owed", "Borrowed", "Paid back", "Last date");
		assertThat(values(sheet, 2)).containsExactly("Ravi", "600.00", "1000.00", "400.00", "46336");
		assertThat(values(sheet, 3)).containsExactly("Priya & <Co>", "250.00", "250.00", "0.00", "46330");
		// an empty row, then Total: the filter covers the header and the 2 friends only, so sorting
		// keeps Total at the bottom (Excel would grow the filter over a Total right under the friends)
		assertThat(sheet.rows().get(3)).isEmpty();
		assertThat(values(sheet, 5)).containsExactly("Total", "850.00", "1250.00", "400.00");
		assertThat(sheet.cells().get("B5").style()).isEqualTo(5);
		assertThat(sheet.xml()).contains("<autoFilter ref=\"A1:E3\"/>").contains("state=\"frozen\"");
	}

	@Test
	void theFriendsFilterEndsAtTheLastFriend() throws Exception {
		ExportService.Snapshot base = fixture(0, false);
		List<DebtsView.Person> people = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			people.add(new DebtsView.Person("Friend " + i, "10.00", "10.00", "0.00", TODAY.toString()));
		}
		ExportService.Snapshot five = new ExportService.Snapshot(base.exportedAt(), base.user(), base.balances(),
				new DebtsView("50.00", people, List.of()), base.entries(), false);
		SheetData sheet = written(five).sheet("Friends you owe");
		assertThat(sheet.cells()).doesNotContainKeys("A7", "B7");
		assertThat(sheet.text("A8")).isEqualTo("Total");
		assertThat(sheet.xml()).contains("<autoFilter ref=\"A1:E6\"/>");
	}

	@Test
	void withNobodyOwedTheFriendsSheetSaysSo() throws Exception {
		ExportService.Snapshot base = fixture(0, false);
		ExportService.Snapshot clear = new ExportService.Snapshot(base.exportedAt(), base.user(), base.balances(),
				new DebtsView("0.00", List.of(), List.of("Ravi")), List.of(), false);
		XlsxTestReader reader = written(clear);
		SheetData friends = reader.sheet("Friends you owe");
		assertThat(friends.rows()).hasSize(1);
		assertThat(friends.text("A1")).isEqualTo("You do not owe any friend money.");
		// a plain sheet: no filter buttons over the sentence, no frozen row
		assertThat(friends.xml()).doesNotContain("autoFilter").doesNotContain("frozen").doesNotContain("<pane");
		SheetData transactions = reader.sheet("Transactions");
		assertThat(transactions.rows()).hasSize(1);
		assertThat(reader.sheet("Summary").text("B14")).isEqualTo("0");
	}

	@Test
	void theStreamIsLeftOpenForTheCaller() throws Exception {
		boolean[] closed = { false };
		OutputStream out = new OutputStream() {
			@Override
			public void write(int b) {
			}

			@Override
			public void close() {
				closed[0] = true;
			}
		};
		excel.write(fixture(0, false), out);
		assertThat(closed[0]).isFalse();
	}

	@Test
	void aFailureHalfWayLeavesTheFileUnfinished() throws Exception {
		ExportService.Snapshot base = fixture(0, false);
		// a date the sheet cannot read makes the Friends sheet fail after the other sheets went out
		DebtsView bad = new DebtsView("10.00",
				List.of(new DebtsView.Person("Ravi", "10.00", "10.00", "0.00", "not a date")), List.of());
		ExportService.Snapshot broken = new ExportService.Snapshot(base.exportedAt(), base.user(), base.balances(), bad,
				base.entries(), false);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertThatThrownBy(() -> excel.write(broken, out)).isInstanceOf(DateTimeParseException.class);
		// no end of the zip: the download is broken, not a whole-looking workbook with a sheet cut short
		assertThat(XlsxTestReader.hasZipEnd(out.toByteArray())).isFalse();
		assertThat(out.size()).isPositive();
	}

	/**
	 * Writes the sample checked by hand in the installed Excel: run with the environment variable
	 * EXCEL_SAMPLE set to the target path. Skipped otherwise.
	 */
	@Test
	@EnabledIfEnvironmentVariable(named = "EXCEL_SAMPLE", matches = ".+")
	void writeSampleForExcel() throws Exception {
		Path target = Path.of(System.getenv("EXCEL_SAMPLE"));
		Files.createDirectories(target.toAbsolutePath().getParent());
		try (OutputStream out = Files.newOutputStream(target)) {
			excel.write(fixture(1_000, false), out);
		}
		assertThat(new XlsxTestReader(Files.readAllBytes(target)).sheet("Transactions").rows()).hasSize(1 + 18 + 1_000);
	}

	/** The values of the filled cells of one row, left to right, without "Added on" (column J). */
	static List<String> values(SheetData sheet, int row) {
		return sheet.rows().get(row - 1).stream().filter(cell -> !cell.ref().startsWith("J")).map(Cell::value).toList();
	}

}
