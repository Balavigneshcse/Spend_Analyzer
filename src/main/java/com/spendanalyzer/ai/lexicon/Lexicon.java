package com.spendanalyzer.ai.lexicon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.spendanalyzer.model.Category;

/**
 * The hand-written knowledge: every known word and phrase with its category. Immutable.
 *
 * <p>A term may sit in two categories ("tablet" the medicine, "tablet" the gadget); then both
 * entries are kept and the rest of the engine has to decide.
 */
public final class Lexicon {

	/** Longest phrase, in tokens, that the matcher will look for. */
	public static final int MAX_PHRASE_TOKENS = 4;

	private final Map<String, List<LexiconEntry>> byTerm = new HashMap<>();

	private final Set<String> words = new HashSet<>();

	private final Set<String> standaloneWords = new HashSet<>();

	private final Set<String> writtenWords = new HashSet<>();

	private final List<LexiconEntry> written;

	private final int canonicalCount;

	/** @param entries the entries as written in the files, in file order */
	public Lexicon(List<LexiconEntry> entries) {
		List<LexiconEntry> kept = new ArrayList<>(entries.size());
		Set<String> canonicals = new LinkedHashSet<>();
		for (LexiconEntry entry : entries) {
			if (add(entry)) {
				kept.add(entry);
				canonicals.add(entry.category() + "/" + entry.canonical());
			}
		}
		// "busticket" and "roomrent" are how phrases get typed on a phone: make them exact hits
		for (LexiconEntry entry : List.copyOf(kept)) {
			if (entry.tokenCount() < 2 || entry.tokenCount() > 3) {
				continue;
			}
			String joined = entry.term().replace(" ", "");
			if (joined.length() >= 5 && joined.length() <= 24 && isLetters(joined) && !byTerm.containsKey(joined)) {
				add(new LexiconEntry(joined, entry.canonical(), entry.category(), entry.weight(), true));
			}
		}
		for (List<LexiconEntry> sameTerm : byTerm.values()) {
			if (sameTerm.size() > 1) {
				sameTerm.sort((a, b) -> Double.compare(b.weight(), a.weight()));
			}
		}
		this.written = List.copyOf(kept);
		this.canonicalCount = canonicals.size();
	}

	private boolean add(LexiconEntry entry) {
		List<LexiconEntry> sameTerm = byTerm.computeIfAbsent(entry.term(), k -> new ArrayList<>(1));
		for (LexiconEntry existing : sameTerm) {
			if (existing.category() == entry.category()) {
				return false; // a repeated line; the first one wins
			}
		}
		sameTerm.add(entry);
		String[] tokens = entry.term().split(" ");
		for (String token : tokens) {
			words.add(token);
		}
		if (tokens.length == 1) {
			standaloneWords.add(entry.term());
			if (!entry.derived()) {
				writtenWords.add(entry.term());
			}
		}
		return true;
	}

	private static boolean isLetters(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c < 'a' || c > 'z') {
				return false;
			}
		}
		return true;
	}

	/** Entries for an exact term ("bus ticket"); empty when unknown. Strongest first. */
	public List<LexiconEntry> find(String term) {
		List<LexiconEntry> found = byTerm.get(term);
		return found == null ? List.of() : found;
	}

	public boolean isTerm(String term) {
		return byTerm.containsKey(term);
	}

	/** True when the token appears in any term, alone or inside a phrase. */
	public boolean isWord(String token) {
		return words.contains(token);
	}

	/** Every token of every term. */
	public Set<String> words() {
		return Set.copyOf(words);
	}

	/** Tokens that are a complete term on their own, including the joined forms of phrases. */
	public Set<String> standaloneWords() {
		return Set.copyOf(standaloneWords);
	}

	/** One-word terms exactly as written in the files (no generated joined forms). */
	public Set<String> writtenWords() {
		return Set.copyOf(writtenWords);
	}

	/** The entries as written in the files (without the joined forms), in file order. */
	public List<LexiconEntry> entries() {
		return written;
	}

	/** Number of distinct items, counting all spellings of one item once. */
	public int canonicalCount() {
		return canonicalCount;
	}

	public int termCount() {
		return byTerm.size();
	}

	public long canonicalCount(Category category) {
		Set<String> seen = new HashSet<>();
		for (LexiconEntry entry : written) {
			if (entry.category() == category) {
				seen.add(entry.canonical());
			}
		}
		return seen.size();
	}

}
