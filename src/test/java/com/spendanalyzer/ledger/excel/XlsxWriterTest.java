package com.spendanalyzer.ledger.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.spendanalyzer.ledger.excel.XlsxTestReader.Cell;
import com.spendanalyzer.ledger.excel.XlsxTestReader.SheetData;
import com.spendanalyzer.ledger.excel.XlsxWriter.Sheet;
import com.spendanalyzer.ledger.excel.XlsxWriter.Style;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class XlsxWriterTest {

	interface Body {

		void write(XlsxWriter book) throws IOException;

	}

	private static byte[] book(List<String> names, Body body) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XlsxWriter book = new XlsxWriter(out, names)) {
			body.write(book);
		}
		return out.toByteArray();
	}

	/** A one-sheet book with one text cell; returns that cell as read back. */
	private static Cell oneText(String text) throws Exception {
		byte[] file = book(List.of("S"), book -> book.startSheet(false).row().text(text));
		return new XlsxTestReader(file).sheet("S").cells().get("A1");
	}

	@Test
	void everyPartIsWellFormedXmlAndTheRelationshipsResolve() throws Exception {
		byte[] file = book(List.of("One", "Two words"), book -> {
			book.startSheet(true, 10, 20).header("A", "B").row().text("x").money(new BigDecimal("1.50"));
			book.startSheet(false, 12).row().text("y");
		});
		XlsxTestReader reader = new XlsxTestReader(file);
		Map<String, byte[]> parts = reader.parts();
		assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
				"xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml");
		for (Map.Entry<String, byte[]> part : parts.entrySet()) {
			// a real XML parser accepts every part
			assertThat(XlsxTestReader.parse(part.getValue())).as(part.getKey()).isNotNull();
		}

		// the package points at the workbook
		Document rootRels = XlsxTestReader.parse(parts.get("_rels/.rels"));
		Element officeDoc = (Element) rootRels.getElementsByTagNameNS(XlsxTestReader.PKG_REL_NS, "Relationship").item(0);
		assertThat(officeDoc.getAttribute("Type")).endsWith("/officeDocument");
		assertThat(parts).containsKey(officeDoc.getAttribute("Target"));

		// every override names an existing part, and every sheet and the styles have one
		Document types = XlsxTestReader.parse(parts.get("[Content_Types].xml"));
		NodeList overrides = types.getElementsByTagName("Override");
		Set<String> typed = new HashSet<>();
		for (int i = 0; i < overrides.getLength(); i++) {
			String name = ((Element) overrides.item(i)).getAttribute("PartName");
			assertThat(parts).containsKey(name.substring(1));
			typed.add(name);
		}
		assertThat(typed).contains("/xl/workbook.xml", "/xl/styles.xml", "/xl/worksheets/sheet1.xml",
				"/xl/worksheets/sheet2.xml");

		// every relationship of the workbook points at a part that exists; the styles are one of them
		Document bookRels = XlsxTestReader.parse(parts.get("xl/_rels/workbook.xml.rels"));
		NodeList rels = bookRels.getElementsByTagNameNS(XlsxTestReader.PKG_REL_NS, "Relationship");
		boolean styles = false;
		for (int i = 0; i < rels.getLength(); i++) {
			Element rel = (Element) rels.item(i);
			assertThat(parts).containsKey("xl/" + rel.getAttribute("Target"));
			styles |= rel.getAttribute("Type").endsWith("/styles");
		}
		assertThat(styles).isTrue();

		assertThat(reader.sheetNames()).containsExactly("One", "Two words");
		assertThat(reader.sheet("One").text("A2")).isEqualTo("x");
		assertThat(reader.sheet("Two words").text("A1")).isEqualTo("y");
	}

	@Test
	void aTableSheetHasABoldHeaderFrozenWithAFilterAndColumnWidths() throws Exception {
		byte[] file = book(List.of("T"), book -> {
			Sheet sheet = book.startSheet(true, 12.5, 30);
			sheet.header("Date", "What");
			sheet.row().date(LocalDate.of(2026, 11, 11)).text("tea");
			sheet.row().date(LocalDate.of(2026, 11, 10)).text("bus");
		});
		SheetData sheet = new XlsxTestReader(file).sheet("T");
		assertThat(sheet.cells().get("A1").style()).isEqualTo(Style.BOLD.index);
		assertThat(sheet.cells().get("B1").style()).isEqualTo(Style.BOLD.index);
		assertThat(sheet.xml()).contains("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
			.contains("<autoFilter ref=\"A1:B3\"/>")
			.contains("<col min=\"1\" max=\"1\" width=\"12.5\" customWidth=\"1\"/>")
			.contains("<col min=\"2\" max=\"2\" width=\"30\" customWidth=\"1\"/>");
		// the schema order: views, columns, data, filter
		assertThat(sheet.xml().indexOf("<sheetViews>")).isLessThan(sheet.xml().indexOf("<cols>"));
		assertThat(sheet.xml().indexOf("<cols>")).isLessThan(sheet.xml().indexOf("<sheetData>"));
		assertThat(sheet.xml().indexOf("</sheetData>")).isLessThan(sheet.xml().indexOf("<autoFilter"));
	}

	@Test
	void aPlainSheetHasNoFreezeAndNoFilter() throws Exception {
		byte[] file = book(List.of("P"), book -> book.startSheet(false, 10).row().text("a"));
		String xml = new XlsxTestReader(file).sheet("P").xml();
		assertThat(xml).doesNotContain("<pane").doesNotContain("autoFilter");
	}

	@Test
	void specialCharactersAreEscapedAndComeBackExactly() throws Exception {
		String text = "<b>&amp;</b> \"quoted\" it's > 5 & < 6";
		Cell cell = oneText(text);
		assertThat(cell.value()).isEqualTo(text);
		assertThat(cell.type()).isEqualTo("inlineStr");
		assertThat(XlsxWriter.escape("<a href='x'>&\"</a>"))
			.isEqualTo("&lt;a href=&apos;x&apos;&gt;&amp;&quot;&lt;/a&gt;");
	}

	@Test
	void tabsNewLinesAndCarriageReturnsSurvive() throws Exception {
		assertThat(oneText("a\tb\nc\r\nd").value()).isEqualTo("a\tb\nc\r\nd");
	}

	@Test
	void textLikeExcelsCharacterCodeShowsAsTyped() throws Exception {
		// the reader decodes _xHHHH_ like Excel; without the guard these read "billAref" and "code \u0007"
		for (String typed : List.of("bill_x0041_ref", "code _x0007_", "_x0041_", "_xbeef_ and _XBEEF_",
				"_x0041_x0042_", "a__x0041__")) {
			assertThat(oneText(typed).value()).as(typed).isEqualTo(typed);
		}
		assertThat(XlsxWriter.keepUnderscoreEscapes("_x0041_")).isEqualTo("_x005F_x0041_");
		assertThat(XlsxWriter.keepUnderscoreEscapes("_x00e9_")).isEqualTo("_x005F_x00e9_");
		assertThat(XlsxWriter.keepUnderscoreEscapes("_x0041_x0042_")).isEqualTo("_x005F_x0041_x005F_x0042_");
		// no closing underscore, not four hex digits, or a capital X: not Excel's pattern, left alone
		for (String plain : List.of("_x0041", "ends _x0041", "_x00G1_", "_x041_", "_X0041_", "my_xbox_", "x0041_")) {
			assertThat(XlsxWriter.keepUnderscoreEscapes(plain)).as(plain).isEqualTo(plain);
			assertThat(oneText(plain).value()).as(plain).isEqualTo(plain);
		}
		byte[] file = book(List.of("S"), book -> book.startSheet(false).row().text("ref _x0041"));
		assertThat(new XlsxTestReader(file).sheet("S").xml()).contains(">ref _x0041</t>");
	}

	@Test
	void sheetNamesAreNotGivenTheCellUnderscoreGuard() throws Exception {
		assertThat(XlsxWriter.escape("a_x0041_")).isEqualTo("a_x0041_");
		byte[] file = book(List.of("a_x0041_"), book -> book.startSheet(false).row().text("a"));
		assertThat(new XlsxTestReader(file).part("xl/workbook.xml")).contains("name=\"a_x0041_\"");
	}

	@Test
	void endTableKeepsLaterRowsOutOfTheFilter() throws Exception {
		byte[] file = book(List.of("T"), book -> {
			Sheet sheet = book.startSheet(true, 10, 10);
			sheet.header("Name", "Owed");
			sheet.row().text("Ravi").money(new BigDecimal("5"));
			sheet.row().text("Anu").money(new BigDecimal("3"));
			sheet.endTable();
			sheet.row().text("Total", Style.BOLD).money(new BigDecimal("8"), Style.BOLD_MONEY);
		});
		SheetData sheet = new XlsxTestReader(file).sheet("T");
		assertThat(sheet.xml()).contains("<autoFilter ref=\"A1:B3\"/>");
		assertThat(sheet.text("A4")).isEqualTo("Total");
	}

	@Test
	void closingFreesTheZip() throws Exception {
		CountingStream out = new CountingStream();
		XlsxWriter book = new XlsxWriter(out, List.of("A"));
		book.startSheet(false).row().text("a");
		assertThat(book.released()).isFalse();
		book.close();
		// the zip is closed (its Deflater ended), the caller's stream is not
		assertThat(book.released()).isTrue();
		assertThat(out.closed).isFalse();
		assertThat(new XlsxTestReader(out.bytes.toByteArray()).sheet("A").text("A1")).isEqualTo("a");
	}

	@Test
	void abortSendsNothingMoreAndLeavesTheZipUnfinished() throws Exception {
		CountingStream out = new CountingStream();
		XlsxWriter book = new XlsxWriter(out, List.of("A", "B"));
		Sheet sheet = book.startSheet(true, 10);
		sheet.header("x").row().text("half");
		long sent = out.count;
		book.abort();
		assertThat(book.released()).isTrue();
		assertThat(out.count).isEqualTo(sent);
		assertThat(out.closed).isFalse();
		assertThat(XlsxTestReader.hasZipEnd(out.bytes.toByteArray())).isFalse();
		assertThatThrownBy(() -> sheet.row()).isInstanceOf(IllegalStateException.class);
		// closing afterwards does nothing
		book.close();
		assertThat(out.count).isEqualTo(sent);
	}

	@Test
	void aFailedCloseStillFreesTheZip() throws Exception {
		boolean[] broken = { false };
		OutputStream out = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				if (broken[0]) {
					throw new IOException("client went away");
				}
			}
		};
		XlsxWriter book = new XlsxWriter(out, List.of("A", "B"));
		book.startSheet(false).row().text("x".repeat(100_000));
		broken[0] = true;
		assertThatThrownBy(book::close).isInstanceOf(IOException.class).hasMessage("client went away");
		assertThat(book.released()).isTrue();
	}


	@Test
	void charactersXmlForbidsAreDropped() throws Exception {
		String weird = "a\u0000b\u0001c\u000Bd\u001Fe￾f￿g\uD800h\uDC00i";
		assertThat(XlsxWriter.cleanText(weird)).isEqualTo("abcdefghi");
		assertThat(oneText(weird).value()).isEqualTo("abcdefghi");
		// a whole emoji and Tamil stay
		assertThat(oneText("chai 😀 தேநீர்").value()).isEqualTo("chai 😀 தேநீர்");
		// only bad characters: an empty cell, which is not written at all
		byte[] file = book(List.of("S"), book -> book.startSheet(false).row().text("\u0000\u0001").text("x"));
		SheetData sheet = new XlsxTestReader(file).sheet("S");
		assertThat(sheet.cells()).containsOnlyKeys("B1");
	}

	@Test
	void aCellHoldsAtMost32767Characters() throws Exception {
		assertThat(oneText("x".repeat(40_000)).value()).hasSize(XlsxWriter.MAX_CELL_TEXT);
		assertThat(XlsxWriter.cleanText("y".repeat(XlsxWriter.MAX_CELL_TEXT))).hasSize(XlsxWriter.MAX_CELL_TEXT);
		// an emoji across the limit is left out whole, never cut in half
		String atEdge = "z".repeat(XlsxWriter.MAX_CELL_TEXT - 1) + "😀";
		String cut = XlsxWriter.cleanText(atEdge);
		assertThat(cut).hasSize(XlsxWriter.MAX_CELL_TEXT - 1).doesNotContain("\uD83D");
	}

	@Test
	void textThatLooksLikeAFormulaStaysText() throws Exception {
		List<String> tricks = List.of("=1+1", "+1+1", "-1+1", "@SUM(A1)", "=HYPERLINK(\"http://x\",\"click\")",
				"=cmd|' /C calc'!A0");
		byte[] file = book(List.of("S"), book -> {
			Sheet sheet = book.startSheet(false);
			for (String trick : tricks) {
				sheet.row().text(trick);
			}
			sheet.row().text("plain");
		});
		XlsxTestReader reader = new XlsxTestReader(file);
		SheetData sheet = reader.sheet("S");
		for (int i = 0; i < tricks.size(); i++) {
			Cell cell = sheet.cells().get("A" + (i + 1));
			assertThat(cell.value()).isEqualTo(tricks.get(i));
			assertThat(cell.type()).isEqualTo("inlineStr");
			assertThat(cell.formula()).isFalse();
			// shown with Excel's hidden apostrophe, so retyping the cell keeps it text too
			assertThat(cell.style()).isEqualTo(Style.GUARDED_TEXT.index);
		}
		assertThat(sheet.cells().get("A7").style()).isZero();
		for (byte[] part : reader.parts().values()) {
			assertThat(new String(part, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("<f>").doesNotContain("<f ");
		}
		assertThat(reader.part("xl/styles.xml")).contains("quotePrefix=\"1\"");
	}

	@Test
	void moneyIsANumberWithTheRupeeFormat() throws Exception {
		byte[] file = book(List.of("S"), book -> book.startSheet(false)
			.row()
			.money(new BigDecimal("-1234.50"))
			.money(new BigDecimal("999999999.99"))
			.money(new BigDecimal("0.10"))
			.money(null)
			.money(new BigDecimal("5.00"), Style.BOLD_MONEY));
		XlsxTestReader reader = new XlsxTestReader(file);
		SheetData sheet = reader.sheet("S");
		assertThat(sheet.cells().get("A1")).isEqualTo(new Cell("A1", null, Style.MONEY.index, "-1234.50", false));
		assertThat(sheet.text("B1")).isEqualTo("999999999.99");
		assertThat(sheet.text("C1")).isEqualTo("0.10");
		assertThat(sheet.cells()).doesNotContainKey("D1");
		assertThat(sheet.cells().get("E1").style()).isEqualTo(Style.BOLD_MONEY.index);
		Document styles = XlsxTestReader.parse(reader.parts().get("xl/styles.xml"));
		Element format = (Element) styles.getElementsByTagNameNS(XlsxTestReader.MAIN_NS, "numFmt").item(0);
		assertThat(format.getAttribute("numFmtId")).isEqualTo("164");
		assertThat(format.getAttribute("formatCode")).isEqualTo("\"₹\"#,##0.00;-\"₹\"#,##0.00");
		// style 2 (MONEY) and 5 (BOLD_MONEY) use it
		NodeList xfs = ((Element) styles.getElementsByTagNameNS(XlsxTestReader.MAIN_NS, "cellXfs").item(0))
			.getElementsByTagNameNS(XlsxTestReader.MAIN_NS, "xf");
		assertThat(xfs.getLength()).isEqualTo(Style.values().length);
		assertThat(((Element) xfs.item(Style.MONEY.index)).getAttribute("numFmtId")).isEqualTo("164");
		assertThat(((Element) xfs.item(Style.BOLD_MONEY.index)).getAttribute("numFmtId")).isEqualTo("164");
		assertThat(((Element) xfs.item(Style.DATE.index)).getAttribute("numFmtId")).isEqualTo("165");
		assertThat(((Element) xfs.item(Style.DATE_TIME.index)).getAttribute("numFmtId")).isEqualTo("166");
	}

	@Test
	void datesAreExcelSerialNumbers() throws Exception {
		byte[] file = book(List.of("S"), book -> book.startSheet(false)
			.row()
			.date(LocalDate.of(2000, 1, 1))
			.date(LocalDate.of(1900, 3, 1))
			.dateTime(LocalDateTime.of(2000, 1, 1, 12, 0))
			.dateTime(LocalDateTime.of(2026, 11, 11, 18, 30, 15))
			.date(LocalDate.of(1900, 1, 1))
			.number(42));
		SheetData sheet = new XlsxTestReader(file).sheet("S");
		// 36526 is what Excel shows for 1 Jan 2000 with the General format
		assertThat(sheet.cells().get("A1")).isEqualTo(new Cell("A1", null, Style.DATE.index, "36526", false));
		assertThat(sheet.text("B1")).isEqualTo("61");
		assertThat(sheet.cells().get("C1")).isEqualTo(new Cell("C1", null, Style.DATE_TIME.index, "36526.5", false));
		// 18:30:15 is 66615 seconds of 86400
		assertThat(new BigDecimal(sheet.text("D1"))).isEqualByComparingTo("46337.7710069444");
		// before March 1900 Excel's calendar is wrong: plain text instead
		assertThat(sheet.cells().get("E1").type()).isEqualTo("inlineStr");
		assertThat(sheet.cells().get("F1")).isEqualTo(new Cell("F1", null, 0, "42", false));
	}

	@Test
	void sheetNamesFollowExcelsRules() throws Exception {
		XlsxWriter.checkSheetName("Friends you owe");
		XlsxWriter.checkSheetName("x".repeat(31));
		for (String bad : List.of("", "x".repeat(32), "a:b", "a\\b", "a/b", "a?b", "a*b", "a[b", "a]b", "'quoted",
				"end'", "History", "history")) {
			assertThatThrownBy(() -> XlsxWriter.checkSheetName(bad)).as(bad)
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(XlsxWriter.sheetName("a/b:c?d*e[f]g\\h")).isEqualTo("a b c d e f g h");
		assertThat(XlsxWriter.sheetName("y".repeat(40))).hasSize(31);
		assertThat(XlsxWriter.sheetName("'x'")).isEqualTo("x");
		assertThat(XlsxWriter.sheetName("History")).isEqualTo("Sheet");
		assertThat(XlsxWriter.sheetName("::")).isEqualTo("Sheet");
		String emoji = XlsxWriter.sheetName("w".repeat(30) + "😀");
		assertThat(emoji).isEqualTo("w".repeat(30));
		for (String wanted : List.of("a/b", "y".repeat(40), "'x'", "History", "::", "Ravi's [money]")) {
			XlsxWriter.checkSheetName(XlsxWriter.sheetName(wanted));
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertThatThrownBy(() -> new XlsxWriter(out, List.of("Same", "SAME")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new XlsxWriter(out, List.of("a/b"))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new XlsxWriter(out, List.of())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aSheetNameWithSpecialCharactersIsEscapedInTheWorkbook() throws Exception {
		byte[] file = book(List.of("Tom & Jerry's <list>"), book -> book.startSheet(false).row().text("a"));
		assertThat(new XlsxTestReader(file).sheetNames()).containsExactly("Tom & Jerry's <list>");
	}

	@Test
	void sheetsNotWrittenAreAddedEmptySoTheBookStaysValid() throws Exception {
		byte[] file = book(List.of("A", "B", "C"), book -> book.startSheet(false).row().text("only"));
		XlsxTestReader reader = new XlsxTestReader(file);
		assertThat(reader.sheet("B").cells()).isEmpty();
		assertThat(reader.sheet("C").cells()).isEmpty();
	}

	@Test
	void misuseIsRefused() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		XlsxWriter book = new XlsxWriter(out, List.of("A"));
		Sheet sheet = book.startSheet(false);
		assertThatThrownBy(() -> sheet.text("no row yet")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> book.startSheet(false)).isInstanceOf(IllegalStateException.class);
		book.close();
		assertThatThrownBy(() -> sheet.row()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void columnLetters() {
		assertThat(XlsxWriter.column(0)).isEqualTo("A");
		assertThat(XlsxWriter.column(25)).isEqualTo("Z");
		assertThat(XlsxWriter.column(26)).isEqualTo("AA");
		assertThat(XlsxWriter.column(701)).isEqualTo("ZZ");
		assertThat(XlsxWriter.column(702)).isEqualTo("AAA");
		assertThat(XlsxWriter.column(16_383)).isEqualTo("XFD");
	}

	@Test
	void manyRowsAreStreamedOutWhileTheyAreWritten() throws Exception {
		CountingStream out = new CountingStream();
		long sentBeforeClose;
		try (XlsxWriter book = new XlsxWriter(out, List.of("Big"))) {
			Sheet sheet = book.startSheet(true, 12, 30, 12);
			sheet.header("Date", "What", "Amount");
			LocalDate day = LocalDate.of(2026, 1, 1);
			for (int i = 0; i < 100_000; i++) {
				sheet.row().date(day.minusDays(i % 3000)).text("spend number " + i).money(BigDecimal.valueOf(i, 2));
			}
			// the bytes are already on their way before the book is finished
			assertThat(out.count).isGreaterThan(100_000);
			assertThat(sheet.rows()).isEqualTo(100_001);
			sentBeforeClose = out.count;
		}
		assertThat(out.closed).as("the caller's stream stays open").isFalse();
		assertThat(out.count).isGreaterThan(sentBeforeClose);
		SheetData sheet = XlsxTestReader.read(XlsxTestReader.unzip(out.bytes.toByteArray()).get("xl/worksheets/sheet1.xml"));
		assertThat(sheet.rows()).hasSize(100_001);
		assertThat(sheet.text("B100001")).isEqualTo("spend number 99999");
		assertThat(sheet.xml()).contains("<autoFilter ref=\"A1:C100001\"/>");
	}

	/** Counts what reaches it, like the HTTP response would see it. */
	private static final class CountingStream extends OutputStream {

		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		long count;

		boolean closed;

		@Override
		public void write(int b) {
			bytes.write(b);
			count++;
		}

		@Override
		public void write(byte[] b, int off, int len) {
			bytes.write(b, off, len);
			count += len;
		}

		@Override
		public void close() {
			closed = true;
		}

	}

}
