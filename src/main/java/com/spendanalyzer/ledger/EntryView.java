package com.spendanalyzer.ledger;

import com.spendanalyzer.common.Money;

/**
 * The {@code Entry} shape of docs/API.md. Fields that do not apply to the entry type are
 * {@code null} in the JSON. The description and the friend's name are the raw text the user typed:
 * the page must show them with textContent.
 */
public record EntryView(String id, String type, String amount, String account, String person, String paymentMode,
		String description, String category, String categoryLabel, String item, Double aiConfidence, String aiSource,
		boolean needsReview, boolean userCorrected, String date, String createdAt) {

	public static EntryView of(EntryRow row) {
		return new EntryView(row.id().toString(), row.type().name(), Money.format(row.amount()), row.account().name(),
				row.person(), row.paymentMode() == null ? null : row.paymentMode().name(), row.description(),
				row.category() == null ? null : row.category().name(),
				row.category() == null ? null : row.category().label(), row.item(),
				row.aiConfidence() == null ? null : roundConfidence(row.aiConfidence()), row.aiSource(),
				row.needsReview(), row.userCorrected(), row.date().toString(), row.createdAt().toString());
	}

	/** Three decimals: hides the noise of the 4-byte float column (0.88 instead of 0.8799999952). */
	public static double roundConfidence(double confidence) {
		return Math.round(confidence * 1000.0) / 1000.0;
	}

}
