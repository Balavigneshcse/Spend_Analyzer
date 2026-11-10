package com.spendanalyzer.ai.text;

import java.util.ArrayList;
import java.util.List;

/** The typed text after normalising and tokenising. Immutable. */
public record AnalyzedText(List<Token> tokens) {

	public static final int MAX_ITEM_CHARS = 60;

	public static final int MAX_KEY_CHARS = 120;

	/** Used as the item when the text holds no letters at all ("200", "???"). */
	public static final String UNKNOWN_ITEM = "unknown";

	public AnalyzedText {
		tokens = List.copyOf(tokens);
	}

	/** The meaningful words, in order, without fillers and numbers. */
	public List<String> contentWords() {
		List<String> words = new ArrayList<>(tokens.size());
		for (Token token : tokens) {
			if (token.isWord()) {
				words.add(token.text());
			}
		}
		return words;
	}

	public boolean hasContent() {
		for (Token token : tokens) {
			if (token.isWord()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The key under which a user's own choice for this text is remembered. Numbers and fillers
	 * are left out so "juse 2" and "paid for juse" share one memory. A text made only of fillers
	 * ("amma 500") is keyed by those; only a text of nothing but numbers is keyed by them.
	 */
	public String memoryKey() {
		String key = join(Token.Kind.WORD);
		if (key.isEmpty()) {
			key = join(Token.Kind.STOP);
		}
		if (key.isEmpty()) {
			key = join(null);
		}
		return cut(key, MAX_KEY_CHARS);
	}

	/** The text to show as the item when no known word was found: the words, without amounts. */
	public String cleanedItem() {
		String item = join(Token.Kind.WORD);
		if (item.isEmpty()) {
			item = join(Token.Kind.STOP);
		}
		item = cut(item, MAX_ITEM_CHARS);
		return item.isEmpty() ? UNKNOWN_ITEM : item;
	}

	private String join(Token.Kind only) {
		StringBuilder out = new StringBuilder();
		for (Token token : tokens) {
			if (only == null || token.kind() == only) {
				if (!out.isEmpty()) {
					out.append(' ');
				}
				out.append(token.text());
			}
		}
		return out.toString();
	}

	/** Cuts to a length without leaving half of a surrogate pair or a trailing space behind. */
	public static String cut(String text, int max) {
		if (text.length() <= max) {
			return text;
		}
		int end = max;
		if (Character.isHighSurrogate(text.charAt(end - 1))) {
			end--;
		}
		return text.substring(0, end).trim();
	}

}
