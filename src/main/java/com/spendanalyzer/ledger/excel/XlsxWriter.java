package com.spendanalyzer.ledger.excel;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a small Excel workbook (.xlsx, Office Open XML) straight to a stream, with nothing but
 * java.util.zip. Rows go out as they are written, so a big sheet never sits in memory.
 * <p>
 * Text is always an inline string, never a formula: "=1+1" typed by a user stays the text "=1+1".
 * No method of this class writes a {@code <f>} element.
 * <p>
 * Use: {@code new XlsxWriter(out, names)}, then for every name in order {@link #startSheet},
 * rows with {@link Sheet#row()}, and finally {@link #close()}. Closing finishes the zip but does not
 * close {@code out}.
 */
public final class XlsxWriter implements Closeable {

	public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	/** Excel shows at most this many characters in one cell. */
	public static final int MAX_CELL_TEXT = 32_767;

	public static final int MAX_SHEET_NAME = 31;

	/** Excel's last row; a sheet cannot grow past it. */
	static final int MAX_ROWS = 1_048_576;

	/** Day 0 of Excel's calendar as it counts from March 1900 on (the 1900 leap-year bug is before that). */
	private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);

	private static final LocalDate FIRST_SAFE_DATE = LocalDate.of(1900, 3, 1);

	private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);

	private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

	private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

	private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

	/** The look of a cell. The numbers are the positions in the {@code cellXfs} list of styles.xml. */
	public enum Style {

		PLAIN(0), BOLD(1), MONEY(2), DATE(3), DATE_TIME(4), BOLD_MONEY(5),
		/** Text that Excel would read as a formula if retyped: shown with a hidden apostrophe, kept as text. */
		GUARDED_TEXT(6);

		final int index;

		Style(int index) {
			this.index = index;
		}

	}

	private final ZipOutputStream zip;

	private final Writer xml;

	private final List<String> sheetNames;

	private int started;

	private Sheet open;

	private boolean closed;

	/** Set by {@link #abort()}: nothing more reaches the caller's stream. */
	private boolean discard;

	/** True once the zip is closed, which also frees its Deflater's native memory. */
	private boolean released;

	/**
	 * Writes the parts that describe the workbook at once; the sheets follow one by one.
	 * @param sheetNames the tabs in order; each must pass {@link #checkSheetName}
	 */
	public XlsxWriter(OutputStream out, List<String> sheetNames) throws IOException {
		if (sheetNames.isEmpty()) {
			throw new IllegalArgumentException("A workbook needs at least one sheet");
		}
		Set<String> seen = new HashSet<>();
		for (String name : sheetNames) {
			checkSheetName(name);
			// Excel compares tab names without regard to case
			if (!seen.add(name.toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException("Two sheets are named " + name);
			}
		}
		this.sheetNames = List.copyOf(sheetNames);
		// The zip must not close the caller's stream (often the HTTP response)
		this.zip = new ZipOutputStream(new FilterOutputStream(out) {
			@Override
			public void write(int b) throws IOException {
				if (!discard) {
					out.write(b);
				}
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				if (!discard) {
					out.write(b, off, len);
				}
			}

			@Override
			public void flush() throws IOException {
				if (!discard) {
					out.flush();
				}
			}

			// reached only from zip.close(), after the Deflater is ended
			@Override
			public void close() throws IOException {
				released = true;
				flush();
			}
		}, StandardCharsets.UTF_8);
		this.xml = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 16 * 1024);
		part("[Content_Types].xml", contentTypes());
		part("_rels/.rels", HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
				+ "<Relationship Id=\"rId1\" Type=\"" + REL_NS + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
				+ "</Relationships>");
		part("xl/workbook.xml", workbook());
		part("xl/_rels/workbook.xml.rels", workbookRels());
		part("xl/styles.xml", styles());
	}

	/**
	 * Throws when Excel would refuse this tab name: empty, longer than 31 characters, one of
	 * {@code : \ / ? * [ ]}, an apostrophe at either end, or the reserved name "History".
	 */
	public static void checkSheetName(String name) {
		if (name == null || name.isEmpty() || name.length() > MAX_SHEET_NAME) {
			throw new IllegalArgumentException("A sheet name needs 1 to 31 characters: " + name);
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (":\\/?*[]".indexOf(c) >= 0 || c < 0x20) {
				throw new IllegalArgumentException("A sheet name cannot contain " + c + ": " + name);
			}
		}
		if (name.startsWith("'") || name.endsWith("'") || name.equalsIgnoreCase("History")) {
			throw new IllegalArgumentException("Excel does not allow the sheet name " + name);
		}
	}

	/** A tab name Excel accepts, made from any text: bad characters become spaces, then cut to 31. */
	public static String sheetName(String wanted) {
		StringBuilder clean = new StringBuilder();
		String text = wanted == null ? "" : cleanText(wanted);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			clean.append(":\\/?*[]".indexOf(c) >= 0 || c < 0x20 ? ' ' : c);
		}
		String name = clean.toString().strip();
		while (name.startsWith("'") || name.endsWith("'")) {
			name = name.replaceAll("^'+|'+$", "").strip();
		}
		if (name.length() > MAX_SHEET_NAME) {
			int end = MAX_SHEET_NAME;
			// never cut a character made of two chars (an emoji) in half
			if (Character.isHighSurrogate(name.charAt(end - 1))) {
				end--;
			}
			name = name.substring(0, end).strip();
		}
		if (name.isEmpty() || name.equalsIgnoreCase("History")) {
			name = "Sheet";
		}
		return name;
	}

	/**
	 * Starts the next sheet of the list given to the constructor, finishing the one before.
	 * @param table true for a list with a header row: the header stays on screen while scrolling and
	 * gets filter buttons
	 * @param widths the width of each column in characters
	 */
	public Sheet startSheet(boolean table, double... widths) throws IOException {
		if (closed) {
			throw new IllegalStateException("The workbook is closed");
		}
		if (started == sheetNames.size()) {
			throw new IllegalStateException("All " + started + " sheets are written already");
		}
		finishSheet();
		started++;
		zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + started + ".xml"));
		open = new Sheet(table, widths.length);
		xml.write(HEAD);
		xml.write("<worksheet xmlns=\"" + MAIN_NS + "\" xmlns:r=\"" + REL_NS + "\">");
		// sheetViews and cols must come before sheetData in the schema
		if (table) {
			xml.write("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\""
					+ " activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"A2\""
					+ " sqref=\"A2\"/></sheetView></sheetViews>");
		}
		else {
			xml.write("<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>");
		}
		if (widths.length > 0) {
			xml.write("<cols>");
			for (int i = 0; i < widths.length; i++) {
				int n = i + 1;
				xml.write("<col min=\"" + n + "\" max=\"" + n + "\" width=\"" + BigDecimal.valueOf(widths[i]).stripTrailingZeros().toPlainString()
						+ "\" customWidth=\"1\"/>");
			}
			xml.write("</cols>");
		}
		xml.write("<sheetData>");
		return open;
	}

	/**
	 * Finishes the open sheet, writes empty ones for any not started, and ends the zip. The caller's
	 * stream stays open. If finishing fails, the book is given up as in {@link #abort()}.
	 */
	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		try {
			finishSheet();
			// every sheet named in workbook.xml must exist, or Excel calls the file damaged
			while (started < sheetNames.size()) {
				startSheet(false);
				finishSheet();
			}
			xml.flush();
		}
		catch (IOException | RuntimeException | Error failure) {
			abort();
			throw failure;
		}
		closed = true;
		// close, not finish: only close() ends the Deflater and frees its native memory at once
		zip.close();
	}

	/**
	 * Gives up on a book that failed half way. Nothing more is sent, so the download stays broken
	 * (no end of the zip) instead of passing as a whole but shorter workbook, and the zip's memory is
	 * freed. The caller's stream stays open.
	 */
	public void abort() {
		if (released) {
			return;
		}
		closed = true;
		open = null;
		discard = true;
		try {
			zip.close();
		}
		catch (IOException | RuntimeException ignored) {
			// nothing is sent any more; the Deflater is ended even when this fails
		}
	}

	/** True once the zip and its Deflater are freed, by {@link #close()} or {@link #abort()}. */
	boolean released() {
		return released;
	}

	private void finishSheet() throws IOException {
		if (open == null) {
			return;
		}
		open.endRow();
		xml.write("</sheetData>");
		if (open.table && open.columns > 0) {
			int last = open.tableEnd > 0 ? open.tableEnd : open.rows;
			xml.write("<autoFilter ref=\"A1:" + column(open.columns - 1) + Math.max(last, 1) + "\"/>");
		}
		xml.write("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>");
		xml.write("</worksheet>");
		xml.flush();
		zip.closeEntry();
		open = null;
	}

	/** One sheet being written. Only the newest row can take cells. */
	public final class Sheet {

		private final boolean table;

		private final int columns;

		private int rows;

		/** The last row the filter covers; 0 means all rows. */
		private int tableEnd;

		private int cell = -1;

		private Sheet(boolean table, int columns) {
			this.table = table;
			this.columns = columns;
		}

		/** Starts a new row below the last one; cells then fill it from column A. */
		public Sheet row() throws IOException {
			check();
			endRow();
			if (rows == MAX_ROWS) {
				throw new IllegalStateException("A sheet holds at most " + MAX_ROWS + " rows");
			}
			rows++;
			cell = 0;
			xml.write("<row r=\"" + rows + "\">");
			return this;
		}

		/** A row of bold titles. */
		public Sheet header(String... titles) throws IOException {
			row();
			for (String title : titles) {
				text(title, Style.BOLD);
			}
			return this;
		}

		/**
		 * Ends the table at the rows written so far: rows added after it (a total) stay out of the
		 * filter, so sorting never moves them in among the others. Leave an empty row after it: when
		 * the file opens, Excel grows a filter over a filled row right under it.
		 */
		public Sheet endTable() {
			check();
			tableEnd = rows;
			return this;
		}

		/** Leaves the next cell empty. */
		public Sheet skip() {
			next();
			return this;
		}

		/** Text exactly as given; null or empty leaves the cell empty. */
		public Sheet text(String value) throws IOException {
			return text(value, Style.PLAIN);
		}

		public Sheet text(String value, Style style) throws IOException {
			String clean = value == null ? "" : cleanText(value);
			if (clean.isEmpty()) {
				return skip();
			}
			Style used = style == Style.PLAIN && looksLikeFormula(clean) ? Style.GUARDED_TEXT : style;
			start(used, "inlineStr");
			xml.write("<is><t xml:space=\"preserve\">");
			escape(keepUnderscoreEscapes(clean), xml);
			xml.write("</t></is></c>");
			return this;
		}

		/** An amount in rupees, kept as a number so Excel can add it up. */
		public Sheet money(BigDecimal amount) throws IOException {
			return money(amount, Style.MONEY);
		}

		public Sheet money(BigDecimal amount, Style style) throws IOException {
			if (amount == null) {
				return skip();
			}
			return number(amount.toPlainString(), style);
		}

		public Sheet number(long value) throws IOException {
			return number(Long.toString(value), Style.PLAIN);
		}

		/** A real Excel date, so the column sorts and filters by date. */
		public Sheet date(LocalDate date) throws IOException {
			if (date == null) {
				return skip();
			}
			if (date.isBefore(FIRST_SAFE_DATE)) {
				// Excel's calendar is wrong before March 1900; the plain date is clearer there
				return text(date.toString());
			}
			return number(Long.toString(ChronoUnit.DAYS.between(EPOCH, date)), Style.DATE);
		}

		/** A real Excel date and time, to the second. */
		public Sheet dateTime(LocalDateTime time) throws IOException {
			if (time == null) {
				return skip();
			}
			if (time.toLocalDate().isBefore(FIRST_SAFE_DATE)) {
				return text(time.toString());
			}
			BigDecimal days = BigDecimal.valueOf(ChronoUnit.DAYS.between(EPOCH, time.toLocalDate()));
			BigDecimal part = BigDecimal.valueOf(time.toLocalTime().toSecondOfDay())
				.divide(SECONDS_PER_DAY, 10, RoundingMode.HALF_UP);
			return number(days.add(part).stripTrailingZeros().toPlainString(), Style.DATE_TIME);
		}

		private Sheet number(String plain, Style style) throws IOException {
			start(style, null);
			xml.write("<v>");
			xml.write(plain);
			xml.write("</v></c>");
			return this;
		}

		private void start(Style style, String type) throws IOException {
			check();
			if (cell < 0) {
				throw new IllegalStateException("Call row() before adding cells");
			}
			xml.write("<c r=\"");
			xml.write(column(next()));
			xml.write(Integer.toString(rows));
			xml.write('"');
			if (style != Style.PLAIN) {
				xml.write(" s=\"" + style.index + "\"");
			}
			if (type != null) {
				xml.write(" t=\"" + type + "\"");
			}
			xml.write('>');
		}

		private int next() {
			if (cell < 0) {
				throw new IllegalStateException("Call row() before adding cells");
			}
			if (cell == 16_384) {
				throw new IllegalStateException("A row holds at most 16384 cells");
			}
			return cell++;
		}

		private void endRow() throws IOException {
			if (cell >= 0) {
				xml.write("</row>");
				cell = -1;
			}
		}

		private void check() {
			if (open != this) {
				throw new IllegalStateException("This sheet is finished");
			}
		}

		/** How many rows were written so far. */
		public int rows() {
			return rows;
		}

	}

	/**
	 * Text that is safe inside XML 1.0 and fits one Excel cell: characters XML forbids (control
	 * characters other than tab, LF and CR, a half of an emoji alone, U+FFFE, U+FFFF) are dropped,
	 * and the result is cut at {@link #MAX_CELL_TEXT} chars without splitting an emoji.
	 */
	public static String cleanText(String value) {
		StringBuilder out = null;
		int length = value.length();
		for (int i = 0; i < length;) {
			int cp = value.codePointAt(i);
			int width = Character.charCount(cp);
			if (!allowedInXml(cp)) {
				if (out == null) {
					out = new StringBuilder(length).append(value, 0, i);
				}
			}
			else if (out != null) {
				out.appendCodePoint(cp);
			}
			i += width;
		}
		String clean = out == null ? value : out.toString();
		if (clean.length() > MAX_CELL_TEXT) {
			int end = MAX_CELL_TEXT;
			if (Character.isHighSurrogate(clean.charAt(end - 1))) {
				end--;
			}
			clean = clean.substring(0, end);
		}
		return clean;
	}

	/** The XML 1.0 Char production. A lone surrogate comes out of codePointAt as itself and fails here. */
	private static boolean allowedInXml(int cp) {
		return cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD)
				|| (cp >= 0x10000 && cp <= 0x10FFFF);
	}

	/** Text Excel would turn into a formula if the user retypes the cell. */
	static boolean looksLikeFormula(String text) {
		char first = text.charAt(0);
		return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
				|| first == '\n';
	}

	/**
	 * Cell text keeps a typed {@code _xHHHH_} as typed: Excel reads that pattern in a cell as a
	 * character code ("_x0041_" would show "A"), so its underscore is written as Excel's own
	 * {@code _x005F_}. Only for cell text, not for sheet names.
	 */
	static String keepUnderscoreEscapes(String text) {
		int at = text.indexOf("_x");
		if (at < 0) {
			return text;
		}
		StringBuilder out = new StringBuilder(text.length() + 16);
		int from = 0;
		// each underscore is checked on its own, so the closing one of "_x0041_x0042_" is caught too
		for (; at >= 0; at = text.indexOf("_x", at + 1)) {
			if (at + 6 < text.length() && text.charAt(at + 6) == '_' && isHex(text, at + 2, at + 6)) {
				// "_x005F_" is the underscore itself; the "x0041_" after it is then plain text
				out.append(text, from, at).append("_x005F_");
				from = at + 1;
			}
		}
		return out.append(text, from, text.length()).toString();
	}

	private static boolean isHex(String text, int from, int to) {
		for (int i = from; i < to; i++) {
			char c = text.charAt(i);
			if (!(c >= '0' && c <= '9' || c >= 'A' && c <= 'F' || c >= 'a' && c <= 'f')) {
				return false;
			}
		}
		return true;
	}

	/** Escapes the five XML specials; CR is written as a reference so a parser cannot turn it into LF. */
	static void escape(String text, Writer out) throws IOException {
		int length = text.length();
		int from = 0;
		for (int i = 0; i < length; i++) {
			String replacement = switch (text.charAt(i)) {
				case '&' -> "&amp;";
				case '<' -> "&lt;";
				case '>' -> "&gt;";
				case '"' -> "&quot;";
				case '\'' -> "&apos;";
				case '\r' -> "&#13;";
				default -> null;
			};
			if (replacement != null) {
				out.write(text, from, i - from);
				out.write(replacement);
				from = i + 1;
			}
		}
		out.write(text, from, length - from);
	}

	static String escape(String text) {
		StringWriter out = new StringWriter(text.length() + 16);
		try {
			escape(text, out);
		}
		catch (IOException impossible) {
			throw new IllegalStateException(impossible);
		}
		return out.toString();
	}

	/** 0 is A, 25 is Z, 26 is AA. */
	static String column(int index) {
		StringBuilder letters = new StringBuilder(3);
		for (int n = index + 1; n > 0; n = (n - 1) / 26) {
			letters.insert(0, (char) ('A' + (n - 1) % 26));
		}
		return letters.toString();
	}

	private void part(String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		xml.write(content);
		xml.flush();
		zip.closeEntry();
	}

	private String contentTypes() {
		StringBuilder types = new StringBuilder(HEAD);
		types.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
			.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
			.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
			.append("<Override PartName=\"/xl/workbook.xml\"")
			.append(" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
			.append("<Override PartName=\"/xl/styles.xml\"")
			.append(" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
		for (int i = 1; i <= sheetNames.size(); i++) {
			types.append("<Override PartName=\"/xl/worksheets/sheet")
				.append(i)
				.append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
		}
		return types.append("</Types>").toString();
	}

	private String workbook() {
		StringBuilder book = new StringBuilder(HEAD);
		book.append("<workbook xmlns=\"").append(MAIN_NS).append("\" xmlns:r=\"").append(REL_NS).append("\">");
		book.append("<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>");
		for (int i = 0; i < sheetNames.size(); i++) {
			book.append("<sheet name=\"")
				.append(escape(sheetNames.get(i)))
				.append("\" sheetId=\"")
				.append(i + 1)
				.append("\" r:id=\"rId")
				.append(i + 1)
				.append("\"/>");
		}
		return book.append("</sheets></workbook>").toString();
	}

	private String workbookRels() {
		StringBuilder rels = new StringBuilder(HEAD);
		rels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
		for (int i = 1; i <= sheetNames.size(); i++) {
			rels.append("<Relationship Id=\"rId")
				.append(i)
				.append("\" Type=\"")
				.append(REL_NS)
				.append("/worksheet\" Target=\"worksheets/sheet")
				.append(i)
				.append(".xml\"/>");
		}
		rels.append("<Relationship Id=\"rId")
			.append(sheetNames.size() + 1)
			.append("\" Type=\"")
			.append(REL_NS)
			.append("/styles\" Target=\"styles.xml\"/>");
		return rels.append("</Relationships>").toString();
	}

	/**
	 * The cell looks, in the order of {@link Style}. Rupees use Western grouping (1,234,567.50):
	 * Excel rewrites the Indian pattern #,##,##0 into #,##0, so it cannot be relied on.
	 */
	private static String styles() {
		String rupees = "&quot;₹&quot;#,##0.00;-&quot;₹&quot;#,##0.00";
		String font = "<sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/>";
		return HEAD + "<styleSheet xmlns=\"" + MAIN_NS + "\">"
				+ "<numFmts count=\"3\">"
				+ "<numFmt numFmtId=\"164\" formatCode=\"" + rupees + "\"/>"
				+ "<numFmt numFmtId=\"165\" formatCode=\"d mmm yyyy\"/>"
				+ "<numFmt numFmtId=\"166\" formatCode=\"d mmm yyyy h:mm AM/PM\"/>"
				+ "</numFmts>"
				+ "<fonts count=\"2\"><font>" + font + "</font><font><b/>" + font + "</font></fonts>"
				+ "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>"
				+ "<fill><patternFill patternType=\"gray125\"/></fill></fills>"
				+ "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
				+ "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
				+ "<cellXfs count=\"7\">"
				+ "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
				+ "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
				+ "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
				+ "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
				+ "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
				+ "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\""
				+ " applyFont=\"1\"/>"
				+ "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" quotePrefix=\"1\"/>"
				+ "</cellXfs>"
				+ "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
				+ "</styleSheet>";
	}

}
