package com.spendanalyzer.ledger.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads back an .xlsx in tests with the JDK's own zip and XML parsers, independent of the writer.
 * The XML parsers refuse DOCTYPEs, so a file can only pass if it is plain, well-formed XML.
 */
public final class XlsxTestReader {

	public static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

	public static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

	public static final String PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";

	/**
	 * One cell as stored.
	 * @param type the {@code t} attribute (null for a number)
	 * @param style the {@code s} attribute, 0 when absent
	 * @param value the text of an inline string, or the {@code <v>} of a number
	 * @param formula true when the cell has an {@code <f>} element
	 */
	public record Cell(String ref, String type, int style, String value, boolean formula) {
	}

	/** One sheet: its cells by reference ("B3"), its rows in order, and the raw XML. */
	public record SheetData(Map<String, Cell> cells, List<List<Cell>> rows, String xml) {

		public String text(String ref) {
			Cell cell = cells.get(ref);
			return cell == null ? null : cell.value();
		}

	}

	private final Map<String, byte[]> parts;

	public XlsxTestReader(byte[] xlsx) throws IOException {
		this.parts = unzip(xlsx);
	}

	public Map<String, byte[]> parts() {
		return parts;
	}

	public String part(String name) {
		byte[] bytes = parts.get(name);
		return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
	}

	/** True when the bytes hold a zip's end record (PK 5 6), written only when the zip is finished. */
	public static boolean hasZipEnd(byte[] bytes) {
		for (int i = 0; i + 3 < bytes.length; i++) {
			if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 5 && bytes[i + 3] == 6) {
				return true;
			}
		}
		return false;
	}

	public static Map<String, byte[]> unzip(byte[] xlsx) throws IOException {
		Map<String, byte[]> parts = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				zip.transferTo(bytes);
				if (parts.put(entry.getName(), bytes.toByteArray()) != null) {
					throw new IllegalStateException("Part twice in the zip: " + entry.getName());
				}
			}
		}
		return parts;
	}

	/** A namespace-aware DOM parse that refuses DOCTYPEs and external entities. */
	public static Document parse(byte[] xml) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		builder.setErrorHandler(null);
		return builder.parse(new ByteArrayInputStream(xml));
	}

	/** The tab names from xl/workbook.xml, in order. */
	public List<String> sheetNames() throws Exception {
		Document book = parse(parts.get("xl/workbook.xml"));
		NodeList sheets = book.getElementsByTagNameNS(MAIN_NS, "sheet");
		List<String> names = new ArrayList<>();
		for (int i = 0; i < sheets.getLength(); i++) {
			names.add(((Element) sheets.item(i)).getAttribute("name"));
		}
		return names;
	}

	/** The sheet with this tab name, found through workbook.xml and its relationships like Excel does. */
	public SheetData sheet(String name) throws Exception {
		Document book = parse(parts.get("xl/workbook.xml"));
		NodeList sheets = book.getElementsByTagNameNS(MAIN_NS, "sheet");
		String relId = null;
		for (int i = 0; i < sheets.getLength(); i++) {
			Element sheet = (Element) sheets.item(i);
			if (sheet.getAttribute("name").equals(name)) {
				relId = sheet.getAttributeNS(REL_NS, "id");
			}
		}
		if (relId == null) {
			throw new IllegalArgumentException("No sheet " + name);
		}
		Document rels = parse(parts.get("xl/_rels/workbook.xml.rels"));
		NodeList list = rels.getElementsByTagNameNS(PKG_REL_NS, "Relationship");
		for (int i = 0; i < list.getLength(); i++) {
			Element rel = (Element) list.item(i);
			if (rel.getAttribute("Id").equals(relId)) {
				return read(parts.get("xl/" + rel.getAttribute("Target")));
			}
		}
		throw new IllegalStateException("No relationship " + relId);
	}

	private static final java.util.regex.Pattern XSTRING = java.util.regex.Pattern.compile("_x([0-9A-Fa-f]{4})_");

	/**
	 * Cell text as Excel shows it: Excel reads {@code _xHHHH_} in a string as the character with that
	 * code, left to right ("_x005F_" is an underscore).
	 */
	public static String decodeXstring(String stored) {
		return XSTRING.matcher(stored)
			.replaceAll(match -> java.util.regex.Matcher
				.quoteReplacement(String.valueOf((char) Integer.parseInt(match.group(1), 16))));
	}

	/** Streams through a sheet with StAX, so a sheet of many thousand rows reads quickly. */
	public static SheetData read(byte[] xml) throws Exception {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_COALESCING, true);
		XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml), "UTF-8");
		Map<String, Cell> cells = new LinkedHashMap<>();
		List<List<Cell>> rows = new ArrayList<>();
		List<Cell> row = null;
		String ref = null;
		String type = null;
		int style = 0;
		StringBuilder value = null;
		boolean formula = false;
		boolean inText = false;
		while (reader.hasNext()) {
			int event = reader.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				switch (reader.getLocalName()) {
					case "row" -> row = new ArrayList<>();
					case "c" -> {
						ref = reader.getAttributeValue(null, "r");
						type = reader.getAttributeValue(null, "t");
						String s = reader.getAttributeValue(null, "s");
						style = s == null ? 0 : Integer.parseInt(s);
						value = new StringBuilder();
						formula = false;
					}
					case "t", "v" -> inText = true;
					case "f" -> formula = true;
					default -> {
					}
				}
			}
			else if (event == XMLStreamConstants.CHARACTERS && inText) {
				value.append(reader.getText());
			}
			else if (event == XMLStreamConstants.END_ELEMENT) {
				switch (reader.getLocalName()) {
					case "t", "v" -> inText = false;
					case "c" -> {
						String text = "inlineStr".equals(type) ? decodeXstring(value.toString()) : value.toString();
						Cell cell = new Cell(ref, type, style, text, formula);
						cells.put(ref, cell);
						row.add(cell);
					}
					case "row" -> rows.add(row);
					default -> {
					}
				}
			}
		}
		return new SheetData(cells, rows, new String(xml, java.nio.charset.StandardCharsets.UTF_8));
	}

}
