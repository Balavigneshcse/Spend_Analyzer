package com.spendanalyzer.ledger;

import java.math.BigDecimal;

import com.spendanalyzer.model.AccountType;

/** The validated starting balances a new user states. */
public record OpeningBalances(BigDecimal bank, BigDecimal cash, BigDecimal creditCardDue) {

	public BigDecimal of(AccountType account) {
		return switch (account) {
			case BANK -> bank;
			case CASH -> cash;
			case CREDIT_CARD -> creditCardDue;
		};
	}

}
