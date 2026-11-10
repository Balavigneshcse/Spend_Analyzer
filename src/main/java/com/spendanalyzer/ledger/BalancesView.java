package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.util.Map;

import com.spendanalyzer.common.Money;
import com.spendanalyzer.model.AccountType;

/** The {@code Balances} shape of docs/API.md. Money is text with two decimals. */
public record BalancesView(String bank, String cash, String creditCardDue, String available, boolean onboarded) {

	/** An account without a row yet counts as zero. */
	public static BalancesView of(Map<AccountType, BigDecimal> balances, boolean onboarded) {
		BigDecimal bank = balances.getOrDefault(AccountType.BANK, Money.ZERO);
		BigDecimal cash = balances.getOrDefault(AccountType.CASH, Money.ZERO);
		BigDecimal due = balances.getOrDefault(AccountType.CREDIT_CARD, Money.ZERO);
		return new BalancesView(Money.format(bank), Money.format(cash), Money.format(due),
				Money.format(bank.add(cash)), onboarded);
	}

}
