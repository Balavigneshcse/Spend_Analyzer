package com.spendanalyzer.model;

/**
 * The fixed spending categories. The same list is seeded into the {@code categories} table by
 * Flyway (a test keeps the two in step). {@link #OTHER} means "the AI is not sure, please check".
 */
public enum Category {

	FOOD_MEALS("Food & Meals", NeedFlag.NEED),
	SNACKS_BEVERAGES("Snacks & Beverages", NeedFlag.WANT),
	GROCERIES("Groceries & Essentials", NeedFlag.NEED),
	TRANSPORT_FUEL("Transport & Fuel", NeedFlag.NEED),
	EDUCATION_STATIONERY("Education & Stationery", NeedFlag.NEED),
	RENT_UTILITIES("Rent & Utilities", NeedFlag.NEED),
	MOBILE_INTERNET("Mobile & Internet", NeedFlag.NEED),
	HEALTH_MEDICINE("Health & Medicine", NeedFlag.NEED),
	PERSONAL_CARE("Personal Care", NeedFlag.NEED),
	SHOPPING("Shopping", NeedFlag.WANT),
	ENTERTAINMENT_SUBS("Entertainment & Subscriptions", NeedFlag.WANT),
	SOCIAL_GIFTS("Gifts, Treats & Donations", NeedFlag.WANT),
	TRANSFERS_FEES("Transfers, Lending & Fees", NeedFlag.EXCLUDED),
	OTHER("Other", NeedFlag.UNKNOWN);

	private final String label;

	private final NeedFlag needFlag;

	Category(String label, NeedFlag needFlag) {
		this.label = label;
		this.needFlag = needFlag;
	}

	public String label() {
		return label;
	}

	public NeedFlag needFlag() {
		return needFlag;
	}

}
