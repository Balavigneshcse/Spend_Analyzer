package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.NeedFlag;
import com.spendanalyzer.model.PaymentMode;

/** The sums of one list of expenses: total, per category, per mode, per day, per item. */
final class Tally {

	/** An amount with the number of expenses behind it. */
	record Bucket(BigDecimal amount, int count) {

		static final Bucket EMPTY = new Bucket(BigDecimal.ZERO, 0);

		Bucket plus(BigDecimal more) {
			return new Bucket(amount.add(more), count + 1);
		}

	}

	/** All buys of one item. */
	record ItemGroup(String key, Category category, List<BigDecimal> amounts, BigDecimal total) {

		int count() {
			return amounts.size();
		}

		BigDecimal typicalPrice() {
			return Num.median(amounts);
		}

	}

	private final List<Expense> rows;

	private final BigDecimal total;

	private final Map<Category, Bucket> byCategory = new EnumMap<>(Category.class);

	private final Map<PaymentMode, Bucket> byMode = new EnumMap<>(PaymentMode.class);

	private final TreeMap<LocalDate, BigDecimal> byDay = new TreeMap<>();

	private Tally(List<Expense> rows) {
		this.rows = rows;
		this.total = Num.sum(rows);
		for (Expense row : rows) {
			byCategory.merge(row.category(), Bucket.EMPTY.plus(row.amount()), (a, b) -> a.plus(row.amount()));
			byMode.merge(row.mode(), Bucket.EMPTY.plus(row.amount()), (a, b) -> a.plus(row.amount()));
			byDay.merge(row.date(), row.amount(), BigDecimal::add);
		}
	}

	static Tally of(List<Expense> rows) {
		return new Tally(rows);
	}

	List<Expense> rows() {
		return rows;
	}

	BigDecimal total() {
		return total;
	}

	int count() {
		return rows.size();
	}

	boolean isEmpty() {
		return rows.isEmpty();
	}

	Map<Category, Bucket> byCategory() {
		return byCategory;
	}

	Map<PaymentMode, Bucket> byMode() {
		return byMode;
	}

	/** Day totals of the days that had spending, oldest first. */
	TreeMap<LocalDate, BigDecimal> byDay() {
		return byDay;
	}

	Bucket category(Category category) {
		return byCategory.getOrDefault(category, Bucket.EMPTY);
	}

	/** Categories by amount, largest first; the enum order settles ties. */
	List<Map.Entry<Category, Bucket>> categoriesBySize() {
		List<Map.Entry<Category, Bucket>> entries = new ArrayList<>(byCategory.entrySet());
		entries.sort(Comparator.comparing((Map.Entry<Category, Bucket> e) -> e.getValue().amount())
			.reversed()
			.thenComparing(Map.Entry::getKey));
		return entries;
	}

	Bucket flagged(NeedFlag flag) {
		Bucket sum = Bucket.EMPTY;
		for (Map.Entry<Category, Bucket> entry : byCategory.entrySet()) {
			if (entry.getKey().needFlag() == flag) {
				sum = new Bucket(sum.amount().add(entry.getValue().amount()), sum.count() + entry.getValue().count());
			}
		}
		return sum;
	}

	Bucket needsReview() {
		Bucket sum = Bucket.EMPTY;
		for (Expense row : rows) {
			if (row.needsReview()) {
				sum = sum.plus(row.amount());
			}
		}
		return sum;
	}

	/** Buys grouped by item, largest total first; the item name settles ties. */
	List<ItemGroup> items() {
		Map<String, List<Expense>> grouped = new TreeMap<>();
		for (Expense row : rows) {
			grouped.computeIfAbsent(row.itemKey(), key -> new ArrayList<>()).add(row);
		}
		List<ItemGroup> groups = new ArrayList<>();
		for (Map.Entry<String, List<Expense>> entry : grouped.entrySet()) {
			List<BigDecimal> amounts = entry.getValue().stream().map(Expense::amount).toList();
			groups.add(new ItemGroup(entry.getKey(), mainCategory(entry.getValue()), amounts,
					Num.sum(entry.getValue())));
		}
		groups.sort(Comparator.comparing(ItemGroup::total).reversed().thenComparing(ItemGroup::key));
		return groups;
	}

	/** The category most of the money of these rows went to. */
	private static Category mainCategory(List<Expense> rows) {
		Map<Category, BigDecimal> sums = new EnumMap<>(Category.class);
		for (Expense row : rows) {
			sums.merge(row.category(), row.amount(), BigDecimal::add);
		}
		Category best = null;
		for (Map.Entry<Category, BigDecimal> entry : sums.entrySet()) {
			if (best == null || entry.getValue().compareTo(sums.get(best)) > 0) {
				best = entry.getKey();
			}
		}
		return best;
	}

	/** The rows dated from {@code from} to {@code to}, both included. */
	static List<Expense> between(List<Expense> rows, LocalDate from, LocalDate to) {
		List<Expense> out = new ArrayList<>();
		for (Expense row : rows) {
			if (!row.date().isBefore(from) && !row.date().isAfter(to)) {
				out.add(row);
			}
		}
		return out;
	}

}
