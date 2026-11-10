package com.spendanalyzer.ledger;

/** Body of {@code PUT /api/balances/opening}. {@code creditCardDue} may be left out (means 0). */
public record OpeningBalancesRequest(String bank, String cash, String creditCardDue) {
}
