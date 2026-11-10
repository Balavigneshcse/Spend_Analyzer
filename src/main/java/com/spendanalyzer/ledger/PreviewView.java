package com.spendanalyzer.ledger;

import java.util.List;

import com.spendanalyzer.ai.api.Prediction;

/** The answer of {@code POST /api/entries/preview}: the AI's guess with labels for the page. */
public record PreviewView(String category, String categoryLabel, double confidence, String item, String explanation,
		boolean needsReview, String source, List<Alternative> alternatives) {

	public record Alternative(String category, String categoryLabel, double confidence) {
	}

	public static PreviewView of(Prediction prediction) {
		List<Alternative> alternatives = prediction.alternatives()
			.stream()
			.map(a -> new Alternative(a.category().name(), a.category().label(),
					EntryView.roundConfidence(a.confidence())))
			.toList();
		return new PreviewView(prediction.category().name(), prediction.category().label(),
				EntryView.roundConfidence(prediction.confidence()), prediction.item(), prediction.explanation(),
				prediction.needsReview(), prediction.source().name(), alternatives);
	}

}
