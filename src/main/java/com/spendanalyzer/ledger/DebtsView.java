package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import com.spendanalyzer.common.Money;

/**
 * The answer of {@code GET /api/debts} (docs/API.md). Names are the raw text the user typed: the
 * page must show them with textContent.
 *
 * @param total everything owed to friends; the same figure as {@code Balances.owedToFriends}
 * @param people the friends still owed money, the largest debt first
 * @param knownNames every friend ever used, for suggestions while typing
 */
public record DebtsView(String total, List<Person> people, List<String> knownNames) {

	/** More names than this are not useful as suggestions; the most recently used ones are kept. */
	static final int MAX_KNOWN_NAMES = 200;

	/** Without regard to case; the exact spelling only makes the order stable. */
	private static final Comparator<String> BY_NAME = String.CASE_INSENSITIVE_ORDER
		.thenComparing(Comparator.naturalOrder());

	public record Person(String name, String owed, String borrowed, String repaid, String lastDate) {
	}

	public static DebtsView of(List<FriendDebt> friends) {
		BigDecimal total = Money.ZERO;
		for (FriendDebt friend : friends) {
			total = total.add(friend.owed());
		}
		List<Person> people = friends.stream()
			.filter(friend -> friend.owed().signum() > 0)
			.sorted(Comparator.comparing(FriendDebt::owed).reversed().thenComparing(FriendDebt::name, BY_NAME))
			.map(friend -> new Person(friend.name(), Money.format(friend.owed()), Money.format(friend.borrowed()),
					Money.format(friend.repaid()), friend.lastDate().toString()))
			.toList();
		List<String> knownNames = friends.stream()
			.sorted(Comparator.comparing(FriendDebt::lastUsed).reversed().thenComparing(FriendDebt::name, BY_NAME))
			.limit(MAX_KNOWN_NAMES)
			.map(FriendDebt::name)
			.sorted(BY_NAME)
			.toList();
		return new DebtsView(Money.format(total), people, knownNames);
	}

}
