package com.spendanalyzer.model;

/** How a spend was paid, and which balance it touches. */
public enum PaymentMode {

	UPI("UPI", AccountType.BANK),
	DEBIT_CARD("Debit card", AccountType.BANK),
	CREDIT_CARD("Credit card", AccountType.CREDIT_CARD),
	CASH("Cash", AccountType.CASH);

	private final String label;

	private final AccountType account;

	PaymentMode(String label, AccountType account) {
		this.label = label;
		this.account = account;
	}

	public String label() {
		return label;
	}

	/** UPI and debit card take from the bank, cash from cash in hand, credit card adds to the card due. */
	public AccountType account() {
		return account;
	}

}
