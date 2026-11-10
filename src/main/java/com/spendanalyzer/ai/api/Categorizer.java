package com.spendanalyzer.ai.api;

import java.math.BigDecimal;
import java.util.UUID;

import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;

/**
 * The hand-written spend classifier. Given what the user typed ("juse", "briyany", "bus ticket")
 * it decides the category. No external AI service and no ML library is behind this.
 */
public interface Categorizer {

	/**
	 * @param userId whose personal memory to use; {@code null} uses general knowledge only
	 * @param text what the user typed, raw
	 * @param amount may be {@code null}; a weak hint only
	 * @param mode may be {@code null}; a weak hint only
	 * @return never {@code null}; category {@link Category#OTHER} with {@code needsReview} when unsure
	 */
	Prediction categorize(UUID userId, String text, BigDecimal amount, PaymentMode mode);

	/**
	 * Teaches the classifier what this user means by a text. Called when the user corrects a
	 * category ({@code wasCorrection = true}) or keeps the AI's choice ({@code false}). Runs inside the
	 * caller's database transaction and takes effect for the next {@link #categorize} call.
	 */
	void learn(UUID userId, String text, Category finalCategory, boolean wasCorrection);

}
