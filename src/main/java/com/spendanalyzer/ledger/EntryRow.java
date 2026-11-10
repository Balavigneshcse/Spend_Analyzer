package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.EntryType;
import com.spendanalyzer.model.PaymentMode;

/** One row of the {@code entries} table. Never built from request JSON, only by the service. */
public record EntryRow(UUID id, UUID userId, UUID clientId, EntryType type, BigDecimal amount, AccountType account,
		PaymentMode paymentMode, String description, Category category, String item, Category aiCategory,
		Float aiConfidence, String aiSource, boolean needsReview, boolean userCorrected, LocalDate date,
		Instant createdAt, Instant updatedAt) {

	/** What this entry did to the balances when it was applied. */
	public List<Effect> effects() {
		return LedgerEffects.of(type, account, amount);
	}

}
