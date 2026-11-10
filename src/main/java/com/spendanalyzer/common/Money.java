package com.spendanalyzer.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * Money rules in one place. Amounts travel as strings in JSON ("1234.50"), are BigDecimal in Java
 * and NUMERIC(12,2) in the database. Never use double for money.
 */
public final class Money {

	/** Plain digits with at most two decimals. No sign, no exponent, no grouping. */
	private static final Pattern UNSIGNED = Pattern.compile("^(0|[1-9]\\d{0,8})(\\.\\d{1,2})?$");

	public static final BigDecimal ZERO = new BigDecimal("0.00");

	public static final BigDecimal MAX_ENTRY = new BigDecimal("1000000.00");

	public static final BigDecimal MAX_BALANCE = new BigDecimal("999999999.99");

	private Money() {
	}

	/**
	 * Parses an amount typed by the user for the given field.
	 * @throws ApiException 400 with the field name when it is not a plain amount
	 */
	public static BigDecimal parseUnsigned(String field, String raw) {
		if (raw == null || raw.isBlank()) {
			throw ApiException.field(field, "Enter an amount.");
		}
		String value = raw.trim();
		if (!UNSIGNED.matcher(value).matches()) {
			throw ApiException.field(field, "Enter a plain amount like 120 or 49.50.");
		}
		return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
	}

	/** An amount for one entry: above zero and at most {@link #MAX_ENTRY}. */
	public static BigDecimal parseEntryAmount(String field, String raw) {
		BigDecimal amount = parseUnsigned(field, raw);
		if (amount.signum() <= 0) {
			throw ApiException.field(field, "Enter an amount above zero.");
		}
		if (amount.compareTo(MAX_ENTRY) > 0) {
			throw ApiException.field(field, "That amount is too large.");
		}
		return amount;
	}

	/** A balance the user states: zero or more, at most {@link #MAX_BALANCE}. */
	public static BigDecimal parseBalance(String field, String raw) {
		BigDecimal amount = parseUnsigned(field, raw);
		if (amount.compareTo(MAX_BALANCE) > 0) {
			throw ApiException.field(field, "That amount is too large.");
		}
		return amount;
	}

	/** The JSON form: always two decimals, never scientific notation. */
	public static String format(BigDecimal amount) {
		if (amount == null) {
			return null;
		}
		return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

}
