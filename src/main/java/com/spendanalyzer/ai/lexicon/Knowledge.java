package com.spendanalyzer.ai.lexicon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.spendanalyzer.model.Category;

/**
 * Everything the engine knows before it has seen a single user: the hand-written files under
 * {@code src/main/resources/ai}. Loaded once at start-up; a broken line fails fast with the file
 * name and line number, because a silently skipped word is a bug nobody would find.
 *
 * @param stopWords fillers that say nothing about the category ("for", "paid", "ku")
 * @param aliases short forms and what they stand for ("tkt" is "ticket")
 * @param lexicon the known words and phrases
 * @param amountRanges the usual price range of each category, a weak hint for the model
 * @param calibration labelled phrases, kept out of training, used to tune the model's confidence
 */
public record Knowledge(Set<String> stopWords, Map<String, List<String>> aliases, Lexicon lexicon,
		Map<Category, AmountRange> amountRanges, List<LabelledPhrase> calibration) {

	public static final String ROOT = "ai/";

	/** Usual lowest and highest price, in rupees, of things in one category. */
	public record AmountRange(double low, double high) {
	}

	/** A phrase with its right answer. {@code amount} may be null. */
	public record LabelledPhrase(String text, BigDecimal amount, Category category) {
	}

	public static Knowledge load() {
		Map<String, List<String>> aliases = loadAliases();
		Lexicon lexicon = loadLexicon(aliases);
		Set<String> stopWords = loadStopWords();
		// a word cannot be both a filler and an item: the lexicon wins
		stopWords.removeAll(lexicon.standaloneWords());
		return new Knowledge(Set.copyOf(stopWords), Map.copyOf(aliases), lexicon, loadAmountRanges(),
				loadLabelled(ROOT + "calibration.tsv"));
	}

	private static Set<String> loadStopWords() {
		Set<String> words = new HashSet<>();
		for (Line line : lines(ROOT + "stopwords.txt")) {
			for (String word : line.text().split("[\\s,]+")) {
				if (!word.isEmpty()) {
					words.add(requireTerm(word, line));
				}
			}
		}
		return words;
	}

	private static Map<String, List<String>> loadAliases() {
		Map<String, List<String>> aliases = new HashMap<>();
		for (Line line : lines(ROOT + "aliases.tsv")) {
			String[] columns = line.text().split("\t");
			if (columns.length != 2) {
				throw line.broken("expected: alias<TAB>meaning");
			}
			String alias = requireTerm(columns[0].trim(), line);
			if (alias.indexOf(' ') >= 0) {
				throw line.broken("an alias is a single word");
			}
			aliases.put(alias, List.of(requireTerm(columns[1].trim(), line).split(" ")));
		}
		return aliases;
	}

	private static Lexicon loadLexicon(Map<String, List<String>> aliases) {
		List<LexiconEntry> entries = new ArrayList<>(4096);
		for (Category category : Category.values()) {
			if (category == Category.OTHER) {
				continue; // OTHER means "not sure": nothing is ever taught to be OTHER
			}
			String file = ROOT + "lexicon/" + category.name().toLowerCase(Locale.ROOT) + ".tsv";
			for (Line line : lines(file)) {
				String[] columns = line.text().split("\t");
				if (columns.length < 2 || columns.length > 3) {
					throw line.broken("expected: item<TAB>S|M|W<TAB>other spellings");
				}
				String canonical = requireTerm(columns[0].trim(), line);
				double weight = switch (columns[1].trim()) {
					case "S" -> LexiconEntry.STRONG;
					case "M" -> LexiconEntry.MEDIUM;
					case "W" -> LexiconEntry.WEAK;
					default -> throw line.broken("strength must be S, M or W");
				};
				entries.add(entry(canonical, canonical, category, weight, aliases, line));
				if (columns.length == 3) {
					for (String variant : columns[2].split(",")) {
						if (!variant.isBlank()) {
							entries.add(entry(requireTerm(variant.trim(), line), canonical, category, weight, aliases,
									line));
						}
					}
				}
			}
		}
		return new Lexicon(entries);
	}

	/** Short forms are expanded in terms too, so the files and the typed text always agree. */
	private static LexiconEntry entry(String term, String canonical, Category category, double weight,
			Map<String, List<String>> aliases, Line line) {
		StringBuilder expanded = new StringBuilder(term.length());
		int tokens = 0;
		for (String token : term.split(" ")) {
			for (String part : aliases.getOrDefault(token, List.of(token))) {
				if (!expanded.isEmpty()) {
					expanded.append(' ');
				}
				expanded.append(part);
				tokens++;
			}
		}
		if (tokens > Lexicon.MAX_PHRASE_TOKENS) {
			throw line.broken("\"" + term + "\" is longer than " + Lexicon.MAX_PHRASE_TOKENS + " words");
		}
		if (canonical.length() > 60) {
			throw line.broken("item name longer than 60 characters");
		}
		return new LexiconEntry(expanded.toString(), canonical, category, weight, false);
	}

	private static Map<Category, AmountRange> loadAmountRanges() {
		Map<Category, AmountRange> ranges = new EnumMap<>(Category.class);
		for (Line line : lines(ROOT + "amount_ranges.tsv")) {
			String[] columns = line.text().split("\t");
			if (columns.length != 3) {
				throw line.broken("expected: CATEGORY<TAB>low<TAB>high");
			}
			try {
				ranges.put(Category.valueOf(columns[0].trim()),
						new AmountRange(Double.parseDouble(columns[1].trim()), Double.parseDouble(columns[2].trim())));
			}
			catch (IllegalArgumentException ex) {
				throw line.broken(ex.getMessage());
			}
		}
		return ranges;
	}

	/** Reads "text TAB CATEGORY [TAB amount]" lines; also used by tests for their own files. */
	public static List<LabelledPhrase> loadLabelled(String resource) {
		List<LabelledPhrase> phrases = new ArrayList<>();
		for (Line line : lines(resource)) {
			String[] columns = line.text().split("\t");
			if (columns.length < 2) {
				throw line.broken("expected: text<TAB>CATEGORY");
			}
			try {
				BigDecimal amount = columns.length > 2 && !columns[2].isBlank() ? new BigDecimal(columns[2].trim())
						: null;
				phrases.add(new LabelledPhrase(columns[0], amount, Category.valueOf(columns[1].trim())));
			}
			catch (IllegalArgumentException ex) {
				throw line.broken(ex.getMessage());
			}
		}
		return phrases;
	}

	private static String requireTerm(String term, Line line) {
		if (term.isEmpty()) {
			throw line.broken("empty term");
		}
		for (int i = 0; i < term.length(); i++) {
			char c = term.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
					|| (c == ' ' && i > 0 && term.charAt(i - 1) != ' ');
			if (!ok) {
				throw line.broken("\"" + term + "\" may only hold a-z, 0-9 and single spaces");
			}
		}
		return term;
	}

	/** One meaningful line of a resource file (comments and blank lines are skipped). */
	public record Line(String resource, int number, String text) {

		IllegalStateException broken(String why) {
			return new IllegalStateException(resource + " line " + number + ": " + why);
		}

	}

	public static List<Line> lines(String resource) {
		InputStream in = Knowledge.class.getClassLoader().getResourceAsStream(resource);
		if (in == null) {
			throw new IllegalStateException("Missing resource " + resource);
		}
		List<Line> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String text;
			int number = 0;
			while ((text = reader.readLine()) != null) {
				number++;
				if (!text.isBlank() && !text.startsWith("#")) {
					lines.add(new Line(resource, number, text));
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return lines;
	}

}
