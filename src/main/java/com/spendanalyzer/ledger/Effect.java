package com.spendanalyzer.ledger;

import java.math.BigDecimal;

import com.spendanalyzer.model.AccountType;

/**
 * One movement of one balance: add {@code delta} (negative to take away) to {@code account}.
 * For CREDIT_CARD a positive delta means the user owes more.
 */
public record Effect(AccountType account, BigDecimal delta) {
}
