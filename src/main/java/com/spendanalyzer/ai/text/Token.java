package com.spendanalyzer.ai.text;

/**
 * One piece of the typed text.
 *
 * @param text the token after clean-up and alias expansion ("ticket")
 * @param kind what role it plays
 * @param typed what the user actually wrote for it ("tkt"); equal to {@code text} most of the time
 */
public record Token(String text, Kind kind, String typed) {

	public enum Kind {

		/** A word that may carry meaning. */
		WORD,
		/** A filler such as "for", "paid", "ku": kept for phrase matching, ignored otherwise. */
		STOP,
		/** Digits only: a quantity, a price or part of a name such as "5 star". */
		NUMBER

	}

	public boolean isWord() {
		return kind == Kind.WORD;
	}

	public boolean isNumber() {
		return kind == Kind.NUMBER;
	}

}
