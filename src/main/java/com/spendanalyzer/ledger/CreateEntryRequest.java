package com.spendanalyzer.ledger;

/**
 * Body of {@code POST /api/entries}. Everything arrives as text and is checked by
 * {@link EntryRequestParser}; which fields are needed depends on {@code type}. There is no user id
 * here on purpose: the owner always comes from the login session.
 */
public record CreateEntryRequest(String clientId, String type, String date, String description, String amount,
		String paymentMode, String category, String account, String newBalance) {
}
