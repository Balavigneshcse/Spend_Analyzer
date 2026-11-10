package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.spendanalyzer.insight.engine.Tally.ItemGroup;
import com.spendanalyzer.model.NeedFlag;

/**
 * Small leaks: one item from a "want" category bought again and again ("juice 14 times").
 * Each buy is small, the total is not.
 */
final class LeakDetector {

	private LeakDetector() {
	}

	/**
	 * @param rows the expenses of the window to look at
	 * @param worthASentence true for insights: also require a small typical price and a total worth
	 * talking about (rupee floors). false for the health score, which must not depend on the
	 * scale of the amounts
	 */
	static List<ItemGroup> detect(List<Expense> rows, boolean worthASentence) {
		List<Expense> wants = new ArrayList<>();
		for (Expense row : rows) {
			if (row.category().needFlag() == NeedFlag.WANT) {
				wants.add(row);
			}
		}
		BigDecimal spend = Num.sum(rows);
		List<ItemGroup> leaks = new ArrayList<>();
		for (ItemGroup item : Tally.of(wants).items()) {
			if (item.count() < Guardrails.LEAK_MIN_COUNT) {
				continue;
			}
			if (Num.ratio(item.total(), spend) < Guardrails.LEAK_MIN_SHARE) {
				continue;
			}
			if (worthASentence && !(smallPrice(item, spend) && item.total().compareTo(Guardrails.LEAK_MIN_TOTAL) >= 0)) {
				continue;
			}
			leaks.add(item);
		}
		return leaks; // already sorted: largest total first
	}

	private static boolean smallPrice(ItemGroup item, BigDecimal spend) {
		BigDecimal price = item.typicalPrice();
		return price.compareTo(Guardrails.LEAK_SMALL_PRICE) <= 0
				|| Num.ratio(price, spend) <= Guardrails.LEAK_SMALL_PRICE_SHARE;
	}

}
