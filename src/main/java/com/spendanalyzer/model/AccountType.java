package com.spendanalyzer.model;

/**
 * The three balances every user has. BANK and CASH are money owned;
 * CREDIT_CARD is the amount owed on the card (positive = due).
 */
public enum AccountType {

	BANK("Bank account"), CASH("Cash in hand"), CREDIT_CARD("Credit card due");

	private final String label;

	AccountType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
