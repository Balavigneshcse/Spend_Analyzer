package com.spendanalyzer.ledger;

/** Body of {@code POST /api/entries/preview}. Only {@code description} is required. */
public record PreviewRequest(String description, String amount, String paymentMode) {
}
