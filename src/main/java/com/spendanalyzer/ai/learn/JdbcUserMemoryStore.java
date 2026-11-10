package com.spendanalyzer.ai.learn;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.spendanalyzer.model.Category;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * User memory in PostgreSQL: tables {@code ai_user_memory} and {@code ai_user_token_stats}.
 *
 * <p>Every statement is scoped by {@code user_id}. Nothing here opens a transaction: writes join
 * the caller's transaction, so a rolled-back expense also rolls back what the AI learned from it.
 * After each write the oldest rows beyond the per-user cap are deleted, which keeps one user's
 * data small no matter how much they type.
 *
 * <p>The word rows of one lesson are always written in alphabetical order. Two lessons of one user
 * that overlap ("tea samosa" and "samosa tea") then queue up behind each other instead of each
 * holding the row the other one needs.
 */
public final class JdbcUserMemoryStore implements UserMemoryStore {

	public static final int DEFAULT_MAX_TEXTS = 3000;

	public static final int DEFAULT_MAX_WORD_ROWS = 2000;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final int maxTexts;

	private final int maxWordRows;

	public JdbcUserMemoryStore(JdbcClient jdbc, Clock clock, int maxTexts, int maxWordRows) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.maxTexts = Math.max(1, maxTexts);
		this.maxWordRows = Math.max(1, maxWordRows);
	}

	@Override
	public Optional<Category> recall(UUID userId, String key) {
		return jdbc.sql("SELECT category FROM ai_user_memory WHERE user_id = ? AND norm_text = ?")
			.params(userId, key)
			.query(String.class)
			.optional()
			.flatMap(JdbcUserMemoryStore::parse);
	}

	@Override
	public Map<String, Map<Category, Double>> wordWeights(UUID userId, Collection<String> words) {
		if (words.isEmpty()) {
			return Map.of();
		}
		Map<String, Map<Category, Double>> found = new HashMap<>();
		jdbc.sql("SELECT token, category, weight FROM ai_user_token_stats WHERE user_id = :user AND token IN (:tokens)")
			.param("user", userId)
			.param("tokens", List.copyOf(distinct(words)))
			.query((rs, row) -> {
				Optional<Category> category = parse(rs.getString("category"));
				if (category.isPresent()) {
					found.computeIfAbsent(rs.getString("token"), k -> new EnumMap<>(Category.class))
						.put(category.get(), (double) rs.getFloat("weight"));
				}
				return row;
			})
			.list();
		return found;
	}

	@Override
	public void remember(UUID userId, String key, Category category) {
		Timestamp now = Timestamp.from(clock.instant());
		jdbc.sql("""
				INSERT INTO ai_user_memory (user_id, norm_text, category, hits, updated_at)
				VALUES (?, ?, ?, 1, ?)
				ON CONFLICT (user_id, norm_text) DO UPDATE SET
				    hits = CASE WHEN ai_user_memory.category = EXCLUDED.category
				                THEN ai_user_memory.hits + 1 ELSE 1 END,
				    category = EXCLUDED.category,
				    updated_at = EXCLUDED.updated_at
				""").params(userId, key, category.name(), now).update();
		jdbc.sql("""
				DELETE FROM ai_user_memory
				WHERE user_id = ? AND norm_text IN (
				    SELECT norm_text FROM ai_user_memory WHERE user_id = ?
				    ORDER BY updated_at DESC, norm_text OFFSET ?)
				""").params(userId, userId, maxTexts).update();
	}

	@Override
	public void reinforce(UUID userId, Collection<String> words, Category category, double amount, double maxWeight,
			boolean demoteOthers) {
		Set<String> tokens = distinct(words);
		if (tokens.isEmpty()) {
			return;
		}
		Timestamp now = Timestamp.from(clock.instant());
		if (demoteOthers) {
			jdbc.sql("""
					UPDATE ai_user_token_stats SET weight = weight * 0.5
					WHERE user_id = :user AND token IN (:tokens) AND category <> :category
					""")
				.param("user", userId)
				.param("tokens", List.copyOf(tokens))
				.param("category", category.name())
				.update();
		}
		for (String token : tokens) {
			jdbc.sql("""
					INSERT INTO ai_user_token_stats (user_id, token, category, weight, updated_at)
					VALUES (?, ?, ?, ?, ?)
					ON CONFLICT (user_id, token, category) DO UPDATE SET
					    weight = LEAST(CAST(? AS REAL), ai_user_token_stats.weight + EXCLUDED.weight),
					    updated_at = EXCLUDED.updated_at
					""")
				.params(userId, token, category.name(), (float) Math.min(amount, maxWeight), now, (float) maxWeight)
				.update();
		}
		jdbc.sql("""
				DELETE FROM ai_user_token_stats
				WHERE user_id = ? AND (token, category) IN (
				    SELECT token, category FROM ai_user_token_stats WHERE user_id = ?
				    ORDER BY updated_at DESC, token, category OFFSET ?)
				""").params(userId, userId, maxWordRows).update();
	}

	/**
	 * The words once each, sorted, so every caller takes the row locks in the same order. The token
	 * column is VARCHAR(40); the engine never produces longer words, but be safe.
	 */
	private static Set<String> distinct(Collection<String> words) {
		Set<String> tokens = new TreeSet<>();
		for (String word : words) {
			if (word != null && !word.isEmpty() && word.codePointCount(0, word.length()) <= 40) {
				tokens.add(word);
			}
		}
		return tokens;
	}

	/** A category code that is no longer in the enum is treated as "nothing remembered". */
	private static Optional<Category> parse(String code) {
		try {
			return Optional.of(Category.valueOf(code));
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			return Optional.empty();
		}
	}

}
