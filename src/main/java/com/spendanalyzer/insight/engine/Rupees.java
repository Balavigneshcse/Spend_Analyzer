package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rupee amounts for sentences, grouped the Indian way: ₹560, ₹1,250, ₹1,23,456.
 * Whole amounts print without decimals, anything else with two.
 */
public final class Rupees {

	private Rupees() {
	}

	public static String format(BigDecimal amount) {
		BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
		String sign = value.signum() < 0 ? "-" : "";
		String plain = value.abs().toPlainString();
		int dot = plain.indexOf('.');
		String whole = plain.substring(0, dot);
		String paise = plain.substring(dot + 1);
		return sign + "₹" + group(whole) + ("00".equals(paise) ? "" : "." + paise);
	}

	/** Last three digits form one group, every group before that has two digits. */
	private static String group(String digits) {
		if (digits.length() <= 3) {
			return digits;
		}
		String lastThree = digits.substring(digits.length() - 3);
		String rest = digits.substring(0, digits.length() - 3);
		StringBuilder out = new StringBuilder();
		int firstGroup = rest.length() % 2;
		if (firstGroup == 1) {
			out.append(rest.charAt(0));
		}
		for (int i = firstGroup; i < rest.length(); i += 2) {
			if (out.length() > 0) {
				out.append(',');
			}
			out.append(rest, i, i + 2);
		}
		return out.append(',').append(lastThree).toString();
	}

}
