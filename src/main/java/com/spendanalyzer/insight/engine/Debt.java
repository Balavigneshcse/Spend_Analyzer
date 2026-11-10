package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.util.Comparator;

/**
 * Money the user still owes one friend: borrowed and not yet paid back.
 *
 * @param name the friend's name as the user typed it. Plain text and nothing more: it is only ever
 * placed into a sentence as a value
 * @param owed above zero
 */
public record Debt(String name, BigDecimal owed) {

	/** Largest debt first; the name only makes the order stable. */
	static final Comparator<Debt> LARGEST_FIRST = Comparator.comparing(Debt::owed)
		.reversed()
		.thenComparing(Debt::name, String.CASE_INSENSITIVE_ORDER)
		.thenComparing(Debt::name);

}
