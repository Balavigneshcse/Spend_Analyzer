package com.spendanalyzer.ai.fuzzy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the known word a misspelt word most likely stands for. Candidates come from the sound-key
 * buckets and the letter-pair index; the weighted edit distance scores them; equal sound keys add
 * a small bonus. Short words must match almost perfectly, long words may be further off.
 *
 * <p>Thread-safe: the index is immutable and the small result cache is a concurrent map.
 */
public final class FuzzyMatcher {

	/** One accepted candidate. {@code soundsAlike} is true when the full sound keys are equal. */
	public record Match(String word, double score, boolean soundsAlike) {
	}

	/** The best candidate and, when there is one, the runner-up. Both null when nothing is close. */
	public record Result(Match best, Match second) {

		static final Result NONE = new Result(null, null);

		public boolean found() {
			return best != null;
		}

	}

	static final double FULL_KEY_BONUS = 0.05;

	static final double SKELETON_KEY_BONUS = 0.03;

	static final double MAX_SCORE = 0.99;

	/**
	 * Words that are not a hand-written entry of their own (they only occur inside a phrase, or are
	 * the generated joined form of one) lose a little against words that are.
	 */
	static final double NOT_AN_ENTRY_PENALTY = 0.03;

	private static final int MAX_LENGTH_GAP = 3;

	private static final int CANDIDATE_LIMIT = 80;

	private static final int CACHE_LIMIT = 5000;

	private final String[] words;

	private final String[] fullKeys;

	private final String[] skeletonKeys;

	private final boolean[] standsAlone;

	private final Map<String, int[]> byFullKey;

	private final Map<String, int[]> bySkeletonKey;

	private final NgramIndex index;

	private final ConcurrentHashMap<String, Result> cache = new ConcurrentHashMap<>();

	/**
	 * @param vocabulary every known word (lower-case a-z0-9)
	 * @param standalone the subset of words that are hand-written entries on their own
	 */
	public FuzzyMatcher(Collection<String> vocabulary, Collection<String> standalone) {
		// sorted so that ties are broken the same way on every start
		this.words = new TreeSet<>(vocabulary).toArray(new String[0]);
		this.fullKeys = new String[words.length];
		this.skeletonKeys = new String[words.length];
		this.standsAlone = new boolean[words.length];
		Map<String, List<Integer>> full = new HashMap<>();
		Map<String, List<Integer>> skeleton = new HashMap<>();
		Set<String> alone = new HashSet<>(standalone);
		for (int id = 0; id < words.length; id++) {
			fullKeys[id] = PhoneticKey.full(words[id]);
			skeletonKeys[id] = PhoneticKey.skeleton(fullKeys[id]);
			standsAlone[id] = alone.contains(words[id]);
			full.computeIfAbsent(fullKeys[id], k -> new ArrayList<>()).add(id);
			skeleton.computeIfAbsent(skeletonKeys[id], k -> new ArrayList<>()).add(id);
		}
		this.byFullKey = freeze(full);
		this.bySkeletonKey = freeze(skeleton);
		this.index = new NgramIndex(words);
	}

	private static Map<String, int[]> freeze(Map<String, List<Integer>> buckets) {
		Map<String, int[]> frozen = new HashMap<>(buckets.size() * 2);
		buckets.forEach((key, ids) -> frozen.put(key, ids.stream().mapToInt(Integer::intValue).toArray()));
		return frozen;
	}

	/** The minimum score a match needs, by length of the typed word. */
	public static double threshold(int queryLength) {
		if (queryLength <= 3) {
			return 0.90;
		}
		if (queryLength <= 5) {
			return 0.78;
		}
		if (queryLength <= 8) {
			return 0.74;
		}
		return 0.72;
	}

	/** Looks up a word that is NOT in the vocabulary. Words under three letters never match. */
	public Result match(String query) {
		if (query == null || query.length() < 3 || query.length() > 32 || !isPlain(query)) {
			return Result.NONE;
		}
		Result cached = cache.get(query);
		if (cached != null) {
			return cached;
		}
		Result result = search(query);
		if (cache.size() >= CACHE_LIMIT) {
			// crude but bounded: typed words repeat a lot, so refilling is cheap
			cache.clear();
		}
		cache.put(query, result);
		return result;
	}

	private Result search(String query) {
		String fullKey = PhoneticKey.full(query);
		String skeletonKey = PhoneticKey.skeleton(fullKey);
		double threshold = threshold(query.length());
		int[] fromIndex = index.candidates(query, CANDIDATE_LIMIT);
		int[] fromFull = byFullKey.getOrDefault(fullKey, new int[0]);
		// a one or two letter skeleton ("PN") is shared by far too many words to mean anything
		int[] fromSkeleton = skeletonKey.length() >= 3 ? bySkeletonKey.getOrDefault(skeletonKey, new int[0])
				: new int[0];
		int[] candidates = new int[fromIndex.length + fromFull.length + fromSkeleton.length];
		System.arraycopy(fromIndex, 0, candidates, 0, fromIndex.length);
		System.arraycopy(fromFull, 0, candidates, fromIndex.length, fromFull.length);
		System.arraycopy(fromSkeleton, 0, candidates, fromIndex.length + fromFull.length, fromSkeleton.length);
		Arrays.sort(candidates);

		Match best = null;
		Match second = null;
		int previous = -1;
		for (int id : candidates) {
			if (id == previous) {
				continue;
			}
			previous = id;
			String word = words[id];
			if (Math.abs(word.length() - query.length()) > MAX_LENGTH_GAP) {
				continue;
			}
			boolean sameFull = fullKeys[id].equals(fullKey);
			double score = WeightedEditDistance.similarity(query, word);
			if (sameFull) {
				score += FULL_KEY_BONUS;
			}
			if (skeletonKeys[id].equals(skeletonKey)) {
				score += SKELETON_KEY_BONUS;
			}
			score = Math.min(MAX_SCORE, score);
			if (!standsAlone[id]) {
				score -= NOT_AN_ENTRY_PENALTY;
			}
			if (score < threshold) {
				continue;
			}
			Match match = new Match(word, score, sameFull);
			if (best == null || score > best.score()) {
				second = best;
				best = match;
			}
			else if (second == null || score > second.score()) {
				second = match;
			}
		}
		return best == null ? Result.NONE : new Result(best, second);
	}

	private static boolean isPlain(String word) {
		for (int i = 0; i < word.length(); i++) {
			char c = word.charAt(i);
			if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
				return false;
			}
		}
		return true;
	}

}
