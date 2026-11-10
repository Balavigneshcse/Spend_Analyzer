package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What the user owes one friend, added up from the BORROW and REPAY entries of that friend.
 *
 * @param name the spelling of the friend's first entry
 * @param lastDate the latest date of a borrow or pay-back with this friend
 * @param lastUsed when the newest entry with this friend was written
 */
public record FriendDebt(String name, BigDecimal borrowed, BigDecimal repaid, LocalDate lastDate, Instant lastUsed) {

	/** Borrowed and not yet paid back. The ledger service never lets this go below zero. */
	public BigDecimal owed() {
		return borrowed.subtract(repaid);
	}

}
