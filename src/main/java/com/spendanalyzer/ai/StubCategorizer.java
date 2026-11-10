package com.spendanalyzer.ai;

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
 * TEMPORARY placeholder so the application starts before the real engine exists.
 * The AI module deletes this class and provides the real {@link Categorizer} bean.
 */
@Component
public class StubCategorizer implements Categorizer {

	@Override
	public Prediction categorize(UUID userId, String text, BigDecimal amount, PaymentMode mode) {
		String item = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
		if (item.length() > 60) {
			item = item.substring(0, 60);
		}
		if (item.isEmpty()) {
			item = "unknown";
		}
		return new Prediction(Category.OTHER, 0.0, item, "Not recognised yet", true, List.of(),
				PredictionSource.ABSTAIN);
	}

	@Override
	public void learn(UUID userId, String text, Category finalCategory, boolean wasCorrection) {
		// placeholder: nothing to learn
	}

}
