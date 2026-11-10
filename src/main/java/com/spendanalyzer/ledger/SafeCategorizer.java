package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.spendanalyzer.ai.api.Categorizer;
import com.spendanalyzer.ai.api.Prediction;
import com.spendanalyzer.ai.api.PredictionSource;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;

import org.springframework.stereotype.Component;

/**
 * The ledger's door to the AI. It makes sure that whatever the classifier answers fits the
 * database columns (a category, a confidence between 0 and 1, an item of at most 60 characters),
 * so a weak spot in the engine can never break saving a spend.
 */
@Component
public class SafeCategorizer {

	private static final int MAX_ITEM = 60;

	private final Categorizer categorizer;

	public SafeCategorizer(Categorizer categorizer) {
		this.categorizer = categorizer;
	}

	/** Asks the AI. Never returns null parts. Does not teach it anything. */
	public Prediction predict(UUID userId, String text, BigDecimal amount, PaymentMode mode) {
		Prediction raw = categorizer.categorize(userId, text, amount, mode);
		if (raw == null || raw.category() == null) {
			return new Prediction(Category.OTHER, 0.0, fallbackItem(text), "Not recognised", true, List.of(),
					PredictionSource.ABSTAIN);
		}
		double confidence = Double.isNaN(raw.confidence()) ? 0.0 : Math.clamp(raw.confidence(), 0.0, 1.0);
		String item = raw.item() == null || raw.item().isBlank() ? fallbackItem(text) : cut(raw.item().strip());
		List<Prediction.Alternative> alternatives = raw.alternatives() == null ? List.of()
				: raw.alternatives().stream().filter(a -> a != null && a.category() != null).limit(3).toList();
		return new Prediction(raw.category(), confidence, item, raw.explanation() == null ? "" : raw.explanation(),
				raw.needsReview(), alternatives, raw.source() == null ? PredictionSource.ABSTAIN : raw.source());
	}

	/**
	 * Tells the AI which category the user chose for a text.
	 * @param aiCategory what the AI had said for the same text; decides whether this was a correction
	 */
	public void learn(UUID userId, String text, Category chosen, Category aiCategory) {
		categorizer.learn(userId, text, chosen, chosen != aiCategory);
	}

	private static String fallbackItem(String text) {
		String item = cut(text == null ? "" : text.strip().toLowerCase(Locale.ROOT));
		return item.isEmpty() ? "unknown" : item;
	}

	private static String cut(String text) {
		if (text.codePointCount(0, text.length()) <= MAX_ITEM) {
			return text;
		}
		return text.substring(0, text.offsetByCodePoints(0, MAX_ITEM)).strip();
	}

}
