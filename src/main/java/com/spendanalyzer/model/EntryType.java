package com.spendanalyzer.model;

/**
 * Kinds of ledger rows and what they do to the balances.
 * <ul>
 * <li>EXPENSE: UPI / debit card lower BANK, cash lowers CASH, credit card raises the CREDIT_CARD due.</li>
 * <li>INCOME: raises BANK or CASH.</li>
 * <li>WITHDRAWAL: ATM. Lowers BANK, raises CASH.</li>
 * <li>CARD_PAYMENT: paying the credit card bill. Lowers BANK (or CASH) and lowers the CREDIT_CARD due.</li>
 * <li>ADJUSTMENT: the user corrected a balance. The amount is the signed difference.</li>
 * </ul>
 */
public enum EntryType {

	EXPENSE, INCOME, WITHDRAWAL, CARD_PAYMENT, ADJUSTMENT

}
