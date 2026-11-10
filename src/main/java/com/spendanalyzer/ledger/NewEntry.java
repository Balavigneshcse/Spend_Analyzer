package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.EntryType;
import com.spendanalyzer.model.PaymentMode;

/**
 * A validated request to create an entry.
 *
 * @param amount positive; {@code null} for an ADJUSTMENT (the service works out the difference)
 * @param account for an EXPENSE always the account of the payment mode, never a client choice
 * @param paymentMode EXPENSE only
 * @param description required for an EXPENSE, otherwise optional
 * @param category EXPENSE only; {@code null} lets the AI choose
 * @param newBalance ADJUSTMENT only: what the balance should become
 * @param person BORROW and REPAY only: the friend's cleaned name (see {@link PersonName})
 */
public record NewEntry(UUID clientId, EntryType type, LocalDate date, BigDecimal amount, AccountType account,
		PaymentMode paymentMode, String description, Category category, BigDecimal newBalance, String person) {
}
