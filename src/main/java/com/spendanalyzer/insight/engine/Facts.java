package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The computed facts behind one piece of text. A sentence is a template with {slots}; a slot can
 * only be filled from here, so a template cannot invent or compute a number. Tests use
 * {@link #rupeeStrings()} to prove that every rupee figure in a sentence is a computed fact.
 */
final class Facts {

	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

	private record Amount(BigDecimal value) {
	}

	private final Map<String, Object> values = new LinkedHashMap<>();

	static Facts of() {
		return new Facts();
	}

	/** An exact rupee amount. */
	Facts rupees(String key, BigDecimal amount) {
		values.put(key, new Amount(amount));
		return this;
	}

	/** An "about" rupee amount: rounded to whole rupees before it is stored. */
	Facts about(String key, BigDecimal amount) {
		return rupees(key, Num.wholeRupees(amount));
	}

	Facts count(String key, long count) {
		values.put(key, String.valueOf(count));
		return this;
	}

	/** A percentage for a sentence: whole numbers, sign dropped (the template says "more" or "less"). */
	Facts percent(String key, double percent) {
		long whole = Math.round(Math.abs(percent));
		values.put(key, whole == 0 ? "under 1%" : whole + "%");
		return this;
	}

	/** "How many times as much": one decimal below ten, whole numbers above. */
	Facts times(String key, double ratio) {
		values.put(key, ratio >= 10 ? String.valueOf(Math.round(ratio)) : String.format(Locale.ROOT, "%.1f", ratio));
		return this;
	}

	Facts date(String key, LocalDate date) {
		values.put(key, DAY_MONTH.format(date));
		return this;
	}

	Facts text(String key, String text) {
		values.put(key, text);
		return this;
	}

	Facts merge(Facts other) {
		// Keys of the other text get a prefix so they cannot overwrite ours.
		other.values.forEach((key, value) -> values.put("+" + values.size() + ":" + key, value));
		return this;
	}

	/** Replaces every {slot}. Inserted values are never scanned again, so user text cannot inject slots. */
	String fill(String template) {
		StringBuilder out = new StringBuilder(template.length() + 32);
		int at = 0;
		while (at < template.length()) {
			int open = template.indexOf('{', at);
			if (open < 0) {
				out.append(template, at, template.length());
				break;
			}
			int close = template.indexOf('}', open);
			if (close < 0) {
				throw new IllegalStateException("Unclosed slot in template: " + template);
			}
			out.append(template, at, open).append(render(template.substring(open + 1, close), template));
			at = close + 1;
		}
		return out.toString();
	}

	private String render(String key, String template) {
		Object value = values.get(key);
		if (value == null) {
			throw new IllegalStateException("Template uses a fact that was not computed: {" + key + "} in " + template);
		}
		return value instanceof Amount amount ? Rupees.format(amount.value()) : value.toString();
	}

	/** Every rupee amount held here, formatted as a sentence would print it. */
	Set<String> rupeeStrings() {
		Set<String> out = new LinkedHashSet<>();
		for (Object value : values.values()) {
			if (value instanceof Amount amount) {
				out.add(Rupees.format(amount.value()));
			}
		}
		return out;
	}

}
