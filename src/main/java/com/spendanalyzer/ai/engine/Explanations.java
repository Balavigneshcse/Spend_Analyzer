package com.spendanalyzer.ai.engine;

import java.util.List;

import com.spendanalyzer.ai.fuzzy.WeightedEditDistance;
import com.spendanalyzer.ai.lexicon.LexiconMatcher.Span;
import com.spendanalyzer.model.Category;

/**
 * The one-line reasons shown next to a prediction. Plain text only: every quoted piece comes from
 * the cleaned-up text (letters, digits, spaces), never from the raw input.
 */
final class Explanations {

	private Explanations() {
	}

	static String remembered(String text, Category category) {
		return "You chose " + category.label() + " for \"" + text + "\" before.";
	}

	static String taughtWord(String word, Category category) {
		return "You filed \"" + word + "\" under " + category.label() + " before.";
	}

	static String lexicon(Span span, String canonical, Category category) {
		String typed = span.typed();
		if (!span.exact()) {
			// name the word that was matched; add the item only when the two are different words
			// ("briyany" was matched to the spelling "briyani", but the item to show is "biryani")
			String matched = span.matched();
			String shown = sameWord(matched, canonical) ? "\"" + canonical + "\""
					: "\"" + matched + "\" (" + canonical + ")";
			if (span.soundsAlike()) {
				return "\"" + typed + "\" sounds like " + shown + ".";
			}
			return "\"" + typed + "\" looks like a misspelling of " + shown + ".";
		}
		if (typed.equals(canonical)) {
			return "\"" + typed + "\" is a known " + category.label() + " item.";
		}
		return "\"" + typed + "\" is known as \"" + canonical + "\" (" + category.label() + ").";
	}

	static String model(String text, Category category) {
		return "No known item matches \"" + text + "\"; it looks most like " + category.label()
				+ ". Please check.";
	}

	/** True when a matched spelling and its item are the same word written differently. */
	private static boolean sameWord(String matched, String canonical) {
		String a = matched.replace(" ", "");
		String b = canonical.replace(" ", "");
		return a.equals(b) || WeightedEditDistance.similarity(a, b) >= 0.6;
	}

	static String combined(Category category) {
		return "The words here point to more than one category; " + category.label() + " fits best.";
	}

	static String nothingToRead() {
		return "Nothing here says what was bought. Please pick a category.";
	}

	static String unknown(String text) {
		return "I do not recognise \"" + text + "\". Please pick a category.";
	}

	static String unsure(String text, List<Category> candidates) {
		if (candidates.size() >= 2) {
			return "Not sure about \"" + text + "\": it could be " + candidates.get(0).label() + " or "
					+ candidates.get(1).label() + ". Please pick one.";
		}
		return unknown(text);
	}

}
