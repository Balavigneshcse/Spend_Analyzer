package com.spendanalyzer.ledger;

import static com.spendanalyzer.model.AccountType.BANK;
import static com.spendanalyzer.model.AccountType.CASH;
import static com.spendanalyzer.model.AccountType.CREDIT_CARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.EntryType;
import com.spendanalyzer.model.PaymentMode;
import org.junit.jupiter.api.Test;

/** The money rules, one by one. Pure function, no Spring, no database. */
class LedgerEffectsTest {

	private static final BigDecimal AMOUNT = new BigDecimal("40.00");

	private static final BigDecimal MINUS = new BigDecimal("-40.00");

	private static Effect effect(AccountType account, String delta) {
		return new Effect(account, new BigDecimal(delta));
	}

	private static List<Effect> expense(PaymentMode mode, String amount) {
		return LedgerEffects.of(EntryType.EXPENSE, mode.account(), new BigDecimal(amount));
	}

	// ---------------------------------------------------------------- EXPENSE, every payment mode

	@Test
	void expenseByUpiLowersTheBank() {
		assertThat(expense(PaymentMode.UPI, "40.00")).containsExactly(new Effect(BANK, MINUS));
	}

	@Test
	void expenseByDebitCardLowersTheBank() {
		assertThat(expense(PaymentMode.DEBIT_CARD, "40.00")).containsExactly(new Effect(BANK, MINUS));
	}

	@Test
	void expenseInCashLowersCash() {
		assertThat(expense(PaymentMode.CASH, "40.00")).containsExactly(new Effect(CASH, MINUS));
	}

	@Test
	void expenseByCreditCardRaisesTheDueAndTouchesNothingElse() {
		assertThat(expense(PaymentMode.CREDIT_CARD, "40.00")).containsExactly(new Effect(CREDIT_CARD, AMOUNT));
	}

	// ---------------------------------------------------------------- the other types

	@Test
	void incomeRaisesTheChosenBalance() {
		assertThat(LedgerEffects.of(EntryType.INCOME, BANK, AMOUNT)).containsExactly(new Effect(BANK, AMOUNT));
		assertThat(LedgerEffects.of(EntryType.INCOME, CASH, AMOUNT)).containsExactly(new Effect(CASH, AMOUNT));
	}

	@Test
	void withdrawalMovesMoneyFromBankToCash() {
		assertThat(LedgerEffects.of(EntryType.WITHDRAWAL, BANK, AMOUNT)).containsExactly(new Effect(BANK, MINUS),
				new Effect(CASH, AMOUNT));
	}

	@Test
	void cardPaymentLowersTheSourceAndTheDue() {
		assertThat(LedgerEffects.of(EntryType.CARD_PAYMENT, BANK, AMOUNT)).containsExactly(new Effect(BANK, MINUS),
				new Effect(CREDIT_CARD, MINUS));
		assertThat(LedgerEffects.of(EntryType.CARD_PAYMENT, CASH, AMOUNT)).containsExactly(new Effect(CASH, MINUS),
				new Effect(CREDIT_CARD, MINUS));
	}

	@Test
	void adjustmentAppliesTheSignedDifferenceToItsAccount() {
		for (AccountType account : AccountType.values()) {
			assertThat(LedgerEffects.of(EntryType.ADJUSTMENT, account, AMOUNT)).containsExactly(new Effect(account, AMOUNT));
			assertThat(LedgerEffects.of(EntryType.ADJUSTMENT, account, MINUS)).containsExactly(new Effect(account, MINUS));
		}
	}

	@Test
	void accountsThatMakeNoSenseAreRefused() {
		assertThatThrownBy(() -> LedgerEffects.of(EntryType.INCOME, CREDIT_CARD, AMOUNT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> LedgerEffects.of(EntryType.CARD_PAYMENT, CREDIT_CARD, AMOUNT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> LedgerEffects.of(EntryType.WITHDRAWAL, CASH, AMOUNT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> LedgerEffects.of(EntryType.EXPENSE, null, AMOUNT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// ---------------------------------------------------------------- delete = inverse

	@Test
	void inverseUndoesEveryKindOfEntry() {
		for (EntryType type : EntryType.values()) {
			for (AccountType account : AccountType.values()) {
				if (!allowed(type, account)) {
					continue;
				}
				List<Effect> effects = LedgerEffects.of(type, account, AMOUNT);
				List<Effect> both = new ArrayList<>(effects);
				both.addAll(LedgerEffects.inverse(effects));
				assertThat(LedgerEffects.net(both)).as("%s on %s", type, account).isEmpty();
			}
		}
	}

	@Test
	void inverseOfACardPaymentGivesBothBack() {
		assertThat(LedgerEffects.inverse(LedgerEffects.of(EntryType.CARD_PAYMENT, BANK, AMOUNT)))
			.containsExactly(new Effect(BANK, AMOUNT), new Effect(CREDIT_CARD, AMOUNT));
	}

	// ---------------------------------------------------------------- edit = inverse of old + new

	@Test
	void editingTheAmountMovesOnlyTheDifference() {
		assertThat(LedgerEffects.replace(expense(PaymentMode.UPI, "40.00"), expense(PaymentMode.UPI, "55.50")))
			.containsExactly(effect(BANK, "-15.50"));
		assertThat(LedgerEffects.replace(expense(PaymentMode.CREDIT_CARD, "40.00"), expense(PaymentMode.CREDIT_CARD, "25.00")))
			.containsExactly(effect(CREDIT_CARD, "-15.00"));
	}

	@Test
	void editingUpiToCashMovesTheEffectBetweenTheAccounts() {
		assertThat(LedgerEffects.replace(expense(PaymentMode.UPI, "40.00"), expense(PaymentMode.CASH, "40.00")))
			.containsExactly(effect(BANK, "40.00"), effect(CASH, "-40.00"));
	}

	@Test
	void editingCashToCreditCardGivesCashBackAndRaisesTheDue() {
		assertThat(LedgerEffects.replace(expense(PaymentMode.CASH, "40.00"), expense(PaymentMode.CREDIT_CARD, "60.00")))
			.containsExactly(effect(CASH, "40.00"), effect(CREDIT_CARD, "60.00"));
	}

	@Test
	void editingUpiToDebitCardWithTheSameAmountChangesNothing() {
		assertThat(LedgerEffects.replace(expense(PaymentMode.UPI, "40.00"), expense(PaymentMode.DEBIT_CARD, "40.00")))
			.isEmpty();
	}

	// ---------------------------------------------------------------- net and locking order

	@Test
	void netAddsUpPerAccountInFixedOrderAndDropsZeros() {
		List<Effect> mixed = List.of(effect(CREDIT_CARD, "5.00"), effect(CASH, "1.00"), effect(BANK, "-2.00"),
				effect(CASH, "-1.00"), effect(BANK, "-3.00"));
		assertThat(LedgerEffects.net(mixed)).containsExactly(effect(BANK, "-5.00"), effect(CREDIT_CARD, "5.00"));
	}

	@Test
	void touchedNamesExactlyTheAccountsOfTheEffects() {
		for (EntryType type : EntryType.values()) {
			for (AccountType account : AccountType.values()) {
				if (!allowed(type, account)) {
					continue;
				}
				List<AccountType> fromEffects = LedgerEffects.of(type, account, AMOUNT).stream().map(Effect::account).toList();
				assertThat(LedgerEffects.touched(type, account)).as("%s on %s", type, account)
					.containsExactlyElementsOf(fromEffects)
					.isSorted();
			}
		}
	}

	private static boolean allowed(EntryType type, AccountType account) {
		return switch (type) {
			case EXPENSE, ADJUSTMENT -> true;
			case INCOME, CARD_PAYMENT -> account != CREDIT_CARD;
			case WITHDRAWAL -> account == BANK;
		};
	}

}
