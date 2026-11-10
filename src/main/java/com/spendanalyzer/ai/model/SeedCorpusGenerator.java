package com.spendanalyzer.ai.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.spendanalyzer.ai.lexicon.Knowledge;
import com.spendanalyzer.ai.lexicon.LexiconEntry;
import com.spendanalyzer.model.Category;

/**
 * Writes the model's school book. There is no real data to learn from on day one, so training
 * examples are generated from the hand-written lexicon: every known term on its own, with a
 * number, with a neutral extra word, misspelt, and next to another term of its category. The model
 * then learns letter and sound patterns that reach beyond the exact words listed.
 *
 * <p>Deterministic for a given seed.
 */
public final class SeedCorpusGenerator {

	/** One generated text with its category; {@code amount} is null for about half of them. */
	public record SeedExample(String text, Category category, BigDecimal amount) {
	}

	/** Words that may stand next to anything and must teach the model nothing. */
	private static final String[] NEUTRAL = { "shop", "store", "new", "small", "big", "extra", "again", "online",
			"outside", "college", "campus", "night", "morning", "evening", "friend", "roommate", "home", "urgent",
			"one", "two", "half", "full", "local", "nearby", "weekly", "monthly", "this month", "last week" };

	private static final String CONSONANTS = "bcdfghjklmnprstvz";

	private static final String VOWELS = "aeiou";

	private SeedCorpusGenerator() {
	}

	public static List<SeedExample> generate(Knowledge knowledge, long seed) {
		Random random = new Random(seed);
		List<LexiconEntry> entries = knowledge.lexicon().entries();
		Map<Category, List<LexiconEntry>> byCategory = new EnumMap<>(Category.class);
		for (LexiconEntry entry : entries) {
			byCategory.computeIfAbsent(entry.category(), k -> new ArrayList<>()).add(entry);
		}
		List<SeedExample> examples = new ArrayList<>(entries.size() * 4);
		for (LexiconEntry entry : entries) {
			Category category = entry.category();
			String term = entry.term();
			Knowledge.AmountRange range = knowledge.amountRanges().get(category);
			add(examples, term, category, range, random);
			if (random.nextDouble() < 0.5) {
				String number = String.valueOf(1 + random.nextInt(random.nextBoolean() ? 5 : 500));
				add(examples, random.nextBoolean() ? term + " " + number : number + " " + term, category, range,
						random);
			}
			if (entry.weight() <= LexiconEntry.WEAK) {
				continue; // "ticket", "bill": one plain example is all a vague word deserves
			}
			if (random.nextDouble() < 0.5) {
				String neutral = NEUTRAL[random.nextInt(NEUTRAL.length)];
				add(examples, random.nextBoolean() ? term + " " + neutral : neutral + " " + term, category, range,
						random);
			}
			if (random.nextDouble() < 0.2) {
				// an unknown word next to a known one must not confuse the model
				add(examples, term + " " + nonsense(random), category, range, random);
			}
			if (random.nextDouble() < 0.8) {
				String typo = misspellOneWord(term, random);
				if (!typo.equals(term)) {
					add(examples, typo, category, range, random);
				}
			}
			if (random.nextDouble() < 0.35) {
				String typo = misspellOneWord(term, random);
				if (!typo.equals(term)) {
					add(examples, typo, category, range, random);
				}
			}
			if (entry.tokenCount() > 1 && random.nextDouble() < 0.5) {
				add(examples, term.replace(" ", ""), category, range, random);
			}
			List<LexiconEntry> siblings = byCategory.get(category);
			if (random.nextDouble() < (entry.weight() < LexiconEntry.STRONG ? 0.7 : 0.3)) {
				// "swiggy biryani", "tea samosa": two terms of one category in one text
				LexiconEntry other = siblings.get(random.nextInt(siblings.size()));
				if (!other.term().equals(term) && other.tokenCount() + entry.tokenCount() <= 4) {
					add(examples, random.nextBoolean() ? term + " " + other.term() : other.term() + " " + term,
							category, range, random);
				}
			}
		}
		return examples;
	}

	private static void add(List<SeedExample> examples, String text, Category category, Knowledge.AmountRange range,
			Random random) {
		BigDecimal amount = null;
		if (range != null && random.nextBoolean()) {
			// log-uniform: a 10 rupee tea is as typical of its category as a 150 rupee milkshake
			double value = Math.exp(Math.log(range.low()) + random.nextDouble() * Math.log(range.high() / range.low()));
			amount = BigDecimal.valueOf(Math.max(1, Math.round(value)));
		}
		examples.add(new SeedExample(text, category, amount));
	}

	private static String misspellOneWord(String term, Random random) {
		String[] words = term.split(" ");
		int target = random.nextInt(words.length);
		for (int step = 0; step < words.length; step++) {
			int i = (target + step) % words.length;
			String changed = TypoAugmenter.misspell(words[i], random);
			if (!changed.equals(words[i])) {
				words[i] = changed;
				return String.join(" ", words);
			}
		}
		return term;
	}

	/** A pronounceable made-up word such as "zopaki". */
	private static String nonsense(Random random) {
		int syllables = 2 + random.nextInt(2);
		StringBuilder word = new StringBuilder();
		for (int i = 0; i < syllables; i++) {
			word.append(CONSONANTS.charAt(random.nextInt(CONSONANTS.length())));
			word.append(VOWELS.charAt(random.nextInt(VOWELS.length())));
		}
		return word.toString();
	}

}
