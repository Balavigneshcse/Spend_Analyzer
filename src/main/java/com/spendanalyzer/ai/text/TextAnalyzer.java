package com.spendanalyzer.ai.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Turns raw text into tokens: normalise, split, expand short forms ("tkt" to "ticket"), mark
 * fillers and drop units ("500 ml"). Holds only immutable word lists, so it is thread-safe.
 */
public final class TextAnalyzer {

	public static final int MAX_TOKENS = 12;

	public static final int MAX_TOKEN_CHARS = 24;

	/** Words that only measure the thing bought when they follow a number: "2 kg", "500 ml". */
	private static final Set<String> UNITS = Set.of("kg", "kgs", "g", "gm", "gms", "gram", "grams", "ml", "l", "ltr",
			"litre", "litres", "liter", "pc", "pcs", "piece", "pieces", "nos", "plate", "plates", "cup", "cups",
			"glass", "pkt", "pkts", "packet", "packets", "set", "sets", "box", "dozen", "times");

	private final Set<String> stopWords;

	private final Map<String, List<String>> aliases;

	private final Predicate<String> knownMixedWord;

	/**
	 * @param stopWords fillers that carry no category
	 * @param aliases exact-token short forms and what they stand for (one or more words)
	 * @param knownMixedWord tells whether a letter+digit token such as "a4" or "1mg" is a real word
	 * and must not be split
	 */
	public TextAnalyzer(Set<String> stopWords, Map<String, List<String>> aliases, Predicate<String> knownMixedWord) {
		this.stopWords = Set.copyOf(stopWords);
		this.aliases = Map.copyOf(aliases);
		this.knownMixedWord = knownMixedWord;
	}

	public AnalyzedText analyze(String raw) {
		String normalized = TextNormalizer.normalize(raw);
		if (normalized.isEmpty()) {
			return new AnalyzedText(List.of());
		}
		List<String> pieces = new ArrayList<>();
		for (String chunk : normalized.split(" ")) {
			if (chunk.isEmpty()) {
				continue;
			}
			if (isMixed(chunk) && !knownMixedWord.test(chunk)) {
				splitAtDigitBoundaries(chunk, pieces);
			}
			else {
				pieces.add(chunk);
			}
			if (pieces.size() >= MAX_TOKENS) {
				break;
			}
		}
		List<Token> tokens = new ArrayList<>(pieces.size() + 2);
		for (String piece : pieces) {
			String text = AnalyzedText.cut(piece, MAX_TOKEN_CHARS);
			if (text.isEmpty()) {
				continue;
			}
			if (isNumber(text)) {
				tokens.add(new Token(text, Token.Kind.NUMBER, text));
				continue;
			}
			List<String> expansion = aliases.get(text);
			if (expansion == null) {
				tokens.add(word(text, text));
			}
			else {
				for (String part : expansion) {
					tokens.add(word(part, text));
				}
			}
		}
		List<Token> kept = dropUnits(tokens);
		return new AnalyzedText(kept.size() > MAX_TOKENS ? kept.subList(0, MAX_TOKENS) : kept);
	}

	private Token word(String text, String typed) {
		return new Token(text, stopWords.contains(text) ? Token.Kind.STOP : Token.Kind.WORD, typed);
	}

	/** "milk 500 ml" keeps only "milk 500"; "2 x juice" loses the multiplication sign. */
	private static List<Token> dropUnits(List<Token> tokens) {
		List<Token> kept = new ArrayList<>(tokens.size());
		for (int i = 0; i < tokens.size(); i++) {
			Token token = tokens.get(i);
			boolean afterNumber = i > 0 && tokens.get(i - 1).isNumber();
			boolean beforeNumber = i + 1 < tokens.size() && tokens.get(i + 1).isNumber();
			if (!token.isNumber()) {
				if (afterNumber && UNITS.contains(token.text())) {
					continue;
				}
				if (token.text().equals("x") && (afterNumber || beforeNumber)) {
					continue;
				}
			}
			kept.add(token);
		}
		return kept;
	}

	private static boolean isNumber(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (!isDigit(text.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isMixed(String text) {
		boolean digit = false;
		boolean other = false;
		for (int i = 0; i < text.length(); i++) {
			if (isDigit(text.charAt(i))) {
				digit = true;
			}
			else {
				other = true;
			}
		}
		return digit && other;
	}

	/** "dosa2" gives "dosa", "2"; "199rs" gives "199", "rs". */
	private static void splitAtDigitBoundaries(String chunk, List<String> out) {
		int start = 0;
		for (int i = 1; i <= chunk.length(); i++) {
			if (i == chunk.length() || isDigit(chunk.charAt(i)) != isDigit(chunk.charAt(i - 1))) {
				out.add(chunk.substring(start, i));
				start = i;
			}
		}
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

}
