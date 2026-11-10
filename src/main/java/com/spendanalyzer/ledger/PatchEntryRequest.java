package com.spendanalyzer.ledger;

/** Body of {@code PATCH /api/entries/{id}}. A field that is left out (or null) stays as it is. */
public record PatchEntryRequest(String description, String amount, String paymentMode, String date, String category) {
}
