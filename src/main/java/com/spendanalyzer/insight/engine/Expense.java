package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;

/**
 * One spend as the insight engine sees it.
 *
 * @param item canonical item the categoriser recognised ("juice" for "juse"); may be null
 * @param description what the user typed
 */
public record Expense(LocalDate date, BigDecimal amount, Category category, PaymentMode mode, String item,
		String description, boolean needsReview) {

	/** A total order, so no result depends on the order rows arrive in. */
	static final Comparator<Expense> CANONICAL = Comparator.comparing(Expense::date)
		.thenComparing(Expense::amount)
		.thenComparing(Expense::category)
		.thenComparing(Expense::mode)
		.thenComparing(Expense::itemKey)
		.thenComparing(Expense::label)
		.thenComparing(Expense::needsReview);

	public Expense {
		Objects.requireNonNull(date, "date");
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(mode, "mode");
	}

	/** What repeated buys are grouped by: the canonical item, else the lower-cased description. */
	public String itemKey() {
		String source = item != null && !item.isBlank() ? item : description;
		if (source == null || source.isBlank()) {
			return "unnamed spend";
		}
		return source.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	/** The text to show when a sentence names this spend. */
	public String label() {
		return description != null && !description.isBlank() ? description.trim() : itemKey();
	}

}
