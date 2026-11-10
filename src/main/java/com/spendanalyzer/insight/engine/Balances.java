package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;

/** The user's three balances right now. {@code creditCardDue} is money owed, not money owned. */
public record Balances(BigDecimal bank, BigDecimal cash, BigDecimal creditCardDue) {

	public static final Balances NONE = new Balances(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

	public Balances {
		bank = bank == null ? BigDecimal.ZERO : bank;
		cash = cash == null ? BigDecimal.ZERO : cash;
		creditCardDue = creditCardDue == null ? BigDecimal.ZERO : creditCardDue;
	}

	/** Money the user can actually spend: bank plus cash. */
	public BigDecimal available() {
		return bank.add(cash);
	}

}
