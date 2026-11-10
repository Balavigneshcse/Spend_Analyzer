package com.spendanalyzer.ledger;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Reads what a user owes friends in the API shape. One query, so the total and the list always
 * belong together. Joins the caller's transaction when there is one.
 */
@Component
public class DebtReader {

	private final DebtRepository debts;

	public DebtReader(DebtRepository debts) {
		this.debts = debts;
	}

	public DebtsView read(UUID userId) {
		return DebtsView.of(debts.all(userId));
	}

}
