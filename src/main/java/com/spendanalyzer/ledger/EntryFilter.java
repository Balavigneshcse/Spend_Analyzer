package com.spendanalyzer.ledger;

import java.time.LocalDate;

import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.EntryType;
import com.spendanalyzer.model.PaymentMode;

/**
 * The optional filters of {@code GET /api/entries}, already validated. {@code null} means "do not
 * filter on this".
 *
 * @param needsReviewOnly true to return only entries the AI was unsure about
 * @param text plain text that must occur in the description (any letter case)
 */
public record EntryFilter(LocalDate from, LocalDate to, EntryType type, PaymentMode mode, Category category,
		boolean needsReviewOnly, String text) {

	public static EntryFilter none() {
		return new EntryFilter(null, null, null, null, null, false, null);
	}

}
