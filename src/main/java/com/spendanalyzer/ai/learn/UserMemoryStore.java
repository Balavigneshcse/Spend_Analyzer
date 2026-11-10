package com.spendanalyzer.ai.learn;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.spendanalyzer.model.Category;

/**
 * Where the engine keeps what each user taught it. Two kinds of knowledge:
 * <ul>
 * <li>exact memory: "this text is this category"</li>
 * <li>word weights: how strongly a single word points to a category for this user, so a correction
 * also helps texts that merely contain the word</li>
 * </ul>
 * Everything is keyed by user; nothing a user teaches can reach another user. Implementations must
 * be thread-safe and must keep the data of one user bounded.
 */
public interface UserMemoryStore {

	/** The category this user chose for exactly this text key, if any. */
	Optional<Category> recall(UUID userId, String key);

	/**
	 * The user's word weights for the given words.
	 * @return word to (category to weight); words the user never taught are absent
	 */
	Map<String, Map<Category, Double>> wordWeights(UUID userId, Collection<String> words);

	/** Stores (or replaces) the exact memory for a text key. */
	void remember(UUID userId, String key, Category category);

	/**
	 * Adds {@code amount} to the weight of every given word for the category (capped at
	 * {@code maxWeight}).
	 * @param demoteOthers when true the user's weights of the same words for other categories are
	 * halved first: a correction means the old meaning was wrong
	 */
	void reinforce(UUID userId, Collection<String> words, Category category, double amount, double maxWeight,
			boolean demoteOthers);

	/** Used when no user is involved or learning is switched off: remembers nothing. */
	UserMemoryStore NONE = new UserMemoryStore() {

		@Override
		public Optional<Category> recall(UUID userId, String key) {
			return Optional.empty();
		}

		@Override
		public Map<String, Map<Category, Double>> wordWeights(UUID userId, Collection<String> words) {
			return Map.of();
		}

		@Override
		public void remember(UUID userId, String key, Category category) {
		}

		@Override
		public void reinforce(UUID userId, Collection<String> words, Category category, double amount,
				double maxWeight, boolean demoteOthers) {
		}

	};

}
