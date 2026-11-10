package com.spendanalyzer.ai.learn;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.spendanalyzer.model.Category;

/**
 * Keeps user memory in plain maps. For unit tests and for running the engine without a database;
 * the application itself uses {@link JdbcUserMemoryStore}.
 */
public final class InMemoryUserMemoryStore implements UserMemoryStore {

	private static final class UserData {

		/** Insertion-ordered so the oldest entry can be dropped when the cap is reached. */
		final LinkedHashMap<String, Category> memory = new LinkedHashMap<>();

		final LinkedHashMap<String, EnumMap<Category, Double>> words = new LinkedHashMap<>();

	}

	private final ConcurrentHashMap<UUID, UserData> users = new ConcurrentHashMap<>();

	private final int maxTexts;

	private final int maxWords;

	public InMemoryUserMemoryStore() {
		this(JdbcUserMemoryStore.DEFAULT_MAX_TEXTS, JdbcUserMemoryStore.DEFAULT_MAX_WORD_ROWS);
	}

	public InMemoryUserMemoryStore(int maxTexts, int maxWords) {
		this.maxTexts = maxTexts;
		this.maxWords = maxWords;
	}

	@Override
	public Optional<Category> recall(UUID userId, String key) {
		UserData data = users.get(userId);
		if (data == null) {
			return Optional.empty();
		}
		synchronized (data) {
			return Optional.ofNullable(data.memory.get(key));
		}
	}

	@Override
	public Map<String, Map<Category, Double>> wordWeights(UUID userId, Collection<String> words) {
		UserData data = users.get(userId);
		if (data == null) {
			return Map.of();
		}
		Map<String, Map<Category, Double>> found = new HashMap<>();
		synchronized (data) {
			for (String word : words) {
				EnumMap<Category, Double> weights = data.words.get(word);
				if (weights != null) {
					found.put(word, new EnumMap<>(weights));
				}
			}
		}
		return found;
	}

	@Override
	public void remember(UUID userId, String key, Category category) {
		UserData data = users.computeIfAbsent(userId, id -> new UserData());
		synchronized (data) {
			data.memory.remove(key); // re-insert so it counts as the newest
			data.memory.put(key, category);
			dropOldest(data.memory, maxTexts);
		}
	}

	@Override
	public void reinforce(UUID userId, Collection<String> words, Category category, double amount, double maxWeight,
			boolean demoteOthers) {
		UserData data = users.computeIfAbsent(userId, id -> new UserData());
		synchronized (data) {
			for (String word : words) {
				EnumMap<Category, Double> weights = data.words.remove(word);
				if (weights == null) {
					weights = new EnumMap<>(Category.class);
				}
				if (demoteOthers) {
					weights.replaceAll((other, weight) -> other == category ? weight : weight * 0.5);
				}
				weights.merge(category, amount, (old, add) -> Math.min(maxWeight, old + add));
				data.words.put(word, weights);
			}
			dropOldest(data.words, maxWords);
		}
	}

	private static void dropOldest(LinkedHashMap<String, ?> map, int max) {
		Iterator<String> oldest = map.keySet().iterator();
		while (map.size() > max && oldest.hasNext()) {
			oldest.next();
			oldest.remove();
		}
	}

	/** Number of remembered texts of a user (for tests). */
	public int textCount(UUID userId) {
		UserData data = users.get(userId);
		if (data == null) {
			return 0;
		}
		synchronized (data) {
			return data.memory.size();
		}
	}

	/** Number of remembered words of a user (for tests). */
	public int wordCount(UUID userId) {
		UserData data = users.get(userId);
		if (data == null) {
			return 0;
		}
		synchronized (data) {
			return data.words.size();
		}
	}

}
