package com.spendanalyzer.ai.lexicon;

import com.spendanalyzer.model.Category;

/**
 * One known word or phrase.
 *
 * @param term what is matched in the text, tokens separated by one space ("bus ticket", "briyani")
 * @param canonical the item it stands for ("bus ticket", "biryani"); repeated buys are grouped by it
 * @param category where it belongs
 * @param weight how much the term says about the category: 1.0 for a clear item ("dosa"), 0.6 for a
 * shop or brand that sells many things ("amazon"), 0.3 for a vague word ("ticket", "bill")
 * @param derived true for the joined form the loader adds for every phrase ("busticket")
 */
public record LexiconEntry(String term, String canonical, Category category, double weight, boolean derived) {

	public static final double STRONG = 1.0;

	public static final double MEDIUM = 0.6;

	public static final double WEAK = 0.3;

	public int tokenCount() {
		int count = 1;
		for (int i = 0; i < term.length(); i++) {
			if (term.charAt(i) == ' ') {
				count++;
			}
		}
		return count;
	}

}
