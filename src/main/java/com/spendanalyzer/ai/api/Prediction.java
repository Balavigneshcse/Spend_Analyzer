package com.spendanalyzer.ai.api;

import java.util.List;

import com.spendanalyzer.model.Category;

/**
 * Result of classifying one spend description.
 *
 * @param category the chosen category; {@link Category#OTHER} when the classifier abstains
 * @param confidence 0..1
 * @param item canonical item name used to group repeated buys ("juice" for "juse"). Never null or
 * blank and at most 60 characters: when nothing is recognised it is the cleaned-up text itself
 * @param explanation one short human sentence, e.g. {@code "juse" sounds like "juice"}
 * @param needsReview true when the user should check the category
 * @param alternatives up to three other likely categories, best first (may be empty)
 * @param source which part of the engine decided
 */
public record Prediction(Category category, double confidence, String item, String explanation, boolean needsReview,
		List<Alternative> alternatives, PredictionSource source) {

	public record Alternative(Category category, double confidence) {
	}

}
