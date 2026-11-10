package com.spendanalyzer.ledger;

import java.util.UUID;

import com.spendanalyzer.common.ApiException;

import org.springframework.stereotype.Component;

/** Reads the current balances of a user in the API shape. Joins the caller's transaction when there is one. */
@Component
public class BalanceReader {

	private final AccountRepository accounts;

	public BalanceReader(AccountRepository accounts) {
		this.accounts = accounts;
	}

	public BalancesView read(UUID userId) {
		boolean onboarded = accounts.onboarded(userId)
			.orElseThrow(() -> ApiException.notFound("This account no longer exists."));
		return BalancesView.of(accounts.balances(userId), onboarded);
	}

}
