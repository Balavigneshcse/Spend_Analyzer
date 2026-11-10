package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.EntryType;

/**
 * What a ledger entry does to the balances, as plain data. This is the single place that knows
 * the money rules: creating an entry applies {@link #of}, deleting applies the {@link #inverse},
 * editing applies {@link #replace}. No database, no state.
 */
public final class LedgerEffects {

	private LedgerEffects() {
	}

	/**
	 * @param account the entry's stored account: the account of the payment mode for an EXPENSE,
	 * the chosen one for INCOME / CARD_PAYMENT / ADJUSTMENT / BORROW / REPAY, BANK for a WITHDRAWAL
	 * @param amount positive, except for ADJUSTMENT where it is the signed difference
	 */
	public static List<Effect> of(EntryType type, AccountType account, BigDecimal amount) {
		checkAccount(type, account);
		return switch (type) {
			// Spending owned money lowers it; spending on the card raises what is owed.
			case EXPENSE ->
				List.of(new Effect(account, account == AccountType.CREDIT_CARD ? amount : amount.negate()));
			case INCOME -> List.of(new Effect(account, amount));
			case WITHDRAWAL ->
				List.of(new Effect(AccountType.BANK, amount.negate()), new Effect(AccountType.CASH, amount));
			// Paying the bill: the money leaves bank or cash and the amount owed shrinks by the same sum.
			case CARD_PAYMENT ->
				List.of(new Effect(account, amount.negate()), new Effect(AccountType.CREDIT_CARD, amount.negate()));
			case ADJUSTMENT -> List.of(new Effect(account, amount));
			// A friend's money arrives in the bank (UPI) or in the hand (cash); paying back takes it out again.
			// What is owed to the friend is not a balance: it is the sum of these entries themselves.
			case BORROW -> List.of(new Effect(account, amount));
			case REPAY -> List.of(new Effect(account, amount.negate()));
		};
	}

	/** The balances an entry of this kind touches, in locking order. */
	public static List<AccountType> touched(EntryType type, AccountType account) {
		checkAccount(type, account);
		return switch (type) {
			case EXPENSE, INCOME, ADJUSTMENT, BORROW, REPAY -> List.of(account);
			case WITHDRAWAL -> List.of(AccountType.BANK, AccountType.CASH);
			case CARD_PAYMENT -> List.of(account, AccountType.CREDIT_CARD);
		};
	}

	/** Undoes the given effects. */
	public static List<Effect> inverse(List<Effect> effects) {
		return effects.stream().map(e -> new Effect(e.account(), e.delta().negate())).toList();
	}

	/** The movements that turn the balances of an old version of an entry into those of the new one. */
	public static List<Effect> replace(List<Effect> old, List<Effect> updated) {
		List<Effect> all = new ArrayList<>(inverse(old));
		all.addAll(updated);
		return net(all);
	}

	/**
	 * Adds up the effects per account and drops the ones that cancel out. The result is always in
	 * the order BANK, CASH, CREDIT_CARD: every writer updates the account rows in that order, so
	 * two transactions can never wait for each other in a circle.
	 */
	public static List<Effect> net(List<Effect> effects) {
		Map<AccountType, BigDecimal> sums = new EnumMap<>(AccountType.class);
		for (Effect effect : effects) {
			sums.merge(effect.account(), effect.delta(), BigDecimal::add);
		}
		List<Effect> result = new ArrayList<>();
		sums.forEach((account, delta) -> {
			if (delta.signum() != 0) {
				result.add(new Effect(account, delta));
			}
		});
		return List.copyOf(result);
	}

	private static void checkAccount(EntryType type, AccountType account) {
		if (account == null) {
			throw new IllegalArgumentException(type + " needs an account");
		}
		boolean allowed = switch (type) {
			case EXPENSE, ADJUSTMENT -> true;
			case INCOME, CARD_PAYMENT, BORROW, REPAY -> account != AccountType.CREDIT_CARD;
			case WITHDRAWAL -> account == AccountType.BANK;
		};
		if (!allowed) {
			throw new IllegalArgumentException(type + " cannot use account " + account);
		}
	}

}
