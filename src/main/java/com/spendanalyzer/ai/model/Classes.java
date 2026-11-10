package com.spendanalyzer.ai.model;

import com.spendanalyzer.model.Category;

/**
 * The categories the model can answer with, as array positions. {@link Category#OTHER} is not one
 * of them: it means "not sure" and is decided by the confidence rules, never learned.
 */
public final class Classes {

	private static final Category[] BY_INDEX;

	private static final int[] INDEX_OF = new int[Category.values().length];

	static {
		BY_INDEX = new Category[Category.values().length - 1];
		int next = 0;
		for (Category category : Category.values()) {
			INDEX_OF[category.ordinal()] = category == Category.OTHER ? -1 : next;
			if (category != Category.OTHER) {
				BY_INDEX[next++] = category;
			}
		}
	}

	private Classes() {
	}

	public static int count() {
		return BY_INDEX.length;
	}

	public static Category category(int index) {
		return BY_INDEX[index];
	}

	/** @return the position of the category, or -1 for OTHER */
	public static int indexOf(Category category) {
		return INDEX_OF[category.ordinal()];
	}

}
