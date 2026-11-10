package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;

/** A validated edit of an expense. {@code null} means "keep the current value". */
public record EntryChange(String description, BigDecimal amount, PaymentMode paymentMode, LocalDate date,
		Category category) {
}
