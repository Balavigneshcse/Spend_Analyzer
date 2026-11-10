package com.spendanalyzer.ai.lexicon;

import java.util.ArrayList;
import java.util.List;

import com.spendanalyzer.ai.fuzzy.FuzzyMatcher;
import com.spendanalyzer.ai.text.Token;

/**
 * Finds the known words and phrases in a token list. Unknown words are first repaired (plural,
 * joined or split words, then spelling and sound), then the longest known phrase wins at each
 * position: "bus tiket" is one match for "bus ticket", not "bus" plus something else.
 *
 * <p>A word that is itself known is never "corrected", which is what keeps soap and soup, cake and
 * coke, pen and paan apart.
 */
public final class LexiconMatcher {

	/**
	 * One known term found in the text.
	 *
	 * @param entries the entries of the term (more than one when it sits in several categories)
	 * @param similarity 1.0 for an exact hit, lower when spelling had to be repaired
	 * @param soundsAlike the typed word has the same sound key as the term
	 * @param typed what the user wrote for it
	 * @param matched the term as known
	 * @param sourceWords the tokens of the text this span covers, as they were before repair
	 * @param rivalEntries entries of a second, almost as close word in another category (may be empty)
	 * @param rivalSimilarity how close that rival is
	 */
	public record Span(List<LexiconEntry> entries, double similarity, boolean soundsAlike, String typed,
			String matched, List<String> sourceWords, List<LexiconEntry> rivalEntries, double rivalSimilarity) {

		public boolean exact() {
			return similarity >= 1.0;
		}

		public LexiconEntry top() {
			return entries.get(0);
		}

	}

	/**
	 * @param spans the known terms, in text order
	 * @param unknownWords typed words that no term explains
	 * @param matchedWords tokens of the text that are covered by a span
	 */
	public record Result(List<Span> spans, List<String> unknownWords, List<String> matchedWords) {

		public static final Result EMPTY = new Result(List.of(), List.of(), List.of());

	}

	/** A token after repair. */
	private record Piece(String text, Token.Kind kind, String typed, List<String> source, double similarity,
			boolean soundsAlike, boolean unknown, FuzzyMatcher.Match rival) {

		static Piece plain(Token token) {
			return new Piece(token.text(), token.kind(), token.typed(), List.of(token.text()), 1.0, false, false, null);
		}

		static Piece exact(String text, String typed, List<String> source) {
			return new Piece(text, Token.Kind.WORD, typed, source, 1.0, false, false, null);
		}

	}

	private static final int MIN_SPLIT_LENGTH = 6;

	/** Two words are only read as one misspelt phrase when together they are at least this long ... */
	private static final int MIN_PAIR_LENGTH = 7;

	/** ... and this close to the phrase. */
	private static final double MIN_PAIR_SCORE = 0.90;

	private final Lexicon lexicon;

	private final FuzzyMatcher fuzzy;

	public LexiconMatcher(Lexicon lexicon, FuzzyMatcher fuzzy) {
		this.lexicon = lexicon;
		this.fuzzy = fuzzy;
	}

	public Result match(List<Token> tokens) {
		List<Piece> pieces = repair(tokens);
		List<Span> spans = new ArrayList<>(4);
		List<String> unknown = new ArrayList<>(2);
		List<String> matched = new ArrayList<>(4);
		int i = 0;
		while (i < pieces.size()) {
			String term = longestTermAt(pieces, i);
			if (term == null) {
				Piece piece = pieces.get(i);
				if (piece.kind() == Token.Kind.WORD && piece.unknown()) {
					unknown.add(piece.typed());
				}
				i++;
				continue;
			}
			int length = tokenCount(term);
			spans.add(span(pieces, i, length, term));
			matched.addAll(spans.get(spans.size() - 1).sourceWords());
			i += length;
		}
		return new Result(List.copyOf(spans), List.copyOf(unknown), List.copyOf(matched));
	}

	/** The longest known term starting at this position, or null. */
	private String longestTermAt(List<Piece> pieces, int start) {
		int longest = Math.min(Lexicon.MAX_PHRASE_TOKENS, pieces.size() - start);
		for (int length = longest; length >= 1; length--) {
			if (length == 1 && pieces.get(start).kind() != Token.Kind.WORD) {
				return null; // a filler or a number is never a term on its own
			}
			String asTyped = join(pieces, start, length, false);
			if (lexicon.isTerm(asTyped)) {
				return asTyped;
			}
			if (length > 1) {
				// "chiken rice", "mess bil": a known spelling inside a phrase that lists only the usual one
				String usual = join(pieces, start, length, true);
				if (!usual.equals(asTyped) && lexicon.isTerm(usual)) {
					return usual;
				}
			}
		}
		return null;
	}

	private static int tokenCount(String term) {
		int count = 1;
		for (int i = 0; i < term.length(); i++) {
			if (term.charAt(i) == ' ') {
				count++;
			}
		}
		return count;
	}

	private Span span(List<Piece> pieces, int start, int length, String term) {
		List<LexiconEntry> entries = lexicon.find(term);
		double similarity = 1.0;
		boolean soundsAlike = false;
		StringBuilder typed = new StringBuilder();
		List<String> source = new ArrayList<>(length);
		for (int k = start; k < start + length; k++) {
			Piece piece = pieces.get(k);
			if (piece.kind() == Token.Kind.WORD) {
				for (String word : piece.source()) {
					if (!source.contains(word)) {
						source.add(word);
					}
				}
			}
			similarity *= piece.similarity();
			soundsAlike |= piece.soundsAlike();
			// "tkt" expanded to one token and "bday" to one token: do not repeat what was typed
			if (k == start || !piece.typed().equals(pieces.get(k - 1).typed())) {
				if (!typed.isEmpty()) {
					typed.append(' ');
				}
				typed.append(piece.typed());
			}
		}
		List<LexiconEntry> rivals = List.of();
		double rivalSimilarity = 0.0;
		FuzzyMatcher.Match rival = length == 1 ? pieces.get(start).rival() : null;
		if (rival != null) {
			List<LexiconEntry> others = new ArrayList<>(2);
			for (LexiconEntry candidate : lexicon.find(rival.word())) {
				boolean sameCategory = false;
				for (LexiconEntry entry : entries) {
					sameCategory |= entry.category() == candidate.category();
				}
				if (!sameCategory) {
					others.add(candidate);
				}
			}
			if (!others.isEmpty()) {
				rivals = List.copyOf(others);
				rivalSimilarity = rival.score();
			}
		}
		return new Span(entries, similarity, soundsAlike, typed.toString(), term, List.copyOf(source), rivals,
				rivalSimilarity);
	}

	/**
	 * @param usualSpelling replace every word that is a listed spelling of a one-word item by that
	 * item ("chiken" by "chicken")
	 */
	private String join(List<Piece> pieces, int start, int length, boolean usualSpelling) {
		if (length == 1) {
			return pieces.get(start).text();
		}
		StringBuilder out = new StringBuilder();
		for (int k = start; k < start + length; k++) {
			if (k > start) {
				out.append(' ');
			}
			String text = pieces.get(k).text();
			out.append(usualSpelling && pieces.get(k).kind() == Token.Kind.WORD ? usualSpelling(text) : text);
		}
		return out.toString();
	}

	/** "chiken" is "chicken": the one-word item a listed spelling stands for, else the word itself. */
	private String usualSpelling(String word) {
		List<LexiconEntry> entries = lexicon.find(word);
		if (!entries.isEmpty() && entries.get(0).canonical().indexOf(' ') < 0) {
			return entries.get(0).canonical();
		}
		return word;
	}

	/** Replaces every unknown word by its best repair, when there is one. */
	private List<Piece> repair(List<Token> tokens) {
		List<Piece> pieces = new ArrayList<>(tokens.size() + 2);
		int i = 0;
		while (i < tokens.size()) {
			Token token = tokens.get(i);
			if (!token.isWord()) {
				pieces.add(Piece.plain(token));
				i++;
				continue;
			}
			Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
			if (next != null && next.isWord() && !startsKnownPhrase(tokens, i)) {
				Piece pair = repairPair(token, next);
				if (pair != null) {
					pieces.add(pair);
					i += 2;
					continue;
				}
			}
			if (lexicon.isWord(token.text())) {
				pieces.add(Piece.plain(token));
			}
			else {
				repairUnknown(token, pieces);
			}
			i++;
		}
		return pieces;
	}

	/** True when the tokens from this position on spell a known phrase exactly as written. */
	private boolean startsKnownPhrase(List<Token> tokens, int start) {
		StringBuilder phrase = new StringBuilder(tokens.get(start).text());
		StringBuilder usual = new StringBuilder(usualSpelling(tokens.get(start).text()));
		for (int k = start + 1; k < tokens.size() && k < start + Lexicon.MAX_PHRASE_TOKENS; k++) {
			phrase.append(' ').append(tokens.get(k).text());
			usual.append(' ').append(usualSpelling(tokens.get(k).text()));
			if (lexicon.isTerm(phrase.toString()) || lexicon.isTerm(usual.toString())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Two neighbouring words that together are one known term: written apart ("note book" for
	 * "notebook") or a misspelt phrase whose parts happen to be words themselves ("pani pori" for
	 * "pani puri"). Only a very close match is accepted, because both words were readable as typed.
	 */
	private Piece repairPair(Token first, Token second) {
		String joined = first.text() + second.text();
		String typed = first.typed() + " " + second.typed();
		List<String> source = List.of(first.text(), second.text());
		if (lexicon.isTerm(joined)) {
			return Piece.exact(joined, typed, source);
		}
		if (joined.length() < MIN_PAIR_LENGTH) {
			return null;
		}
		FuzzyMatcher.Result result = fuzzy.match(joined);
		if (!result.found() || result.best().score() < MIN_PAIR_SCORE) {
			return null;
		}
		// the match must stand for a phrase ("panipuri" is "pani puri"), not be some long single word
		for (LexiconEntry entry : lexicon.find(result.best().word())) {
			if (entry.derived() || entry.canonical().indexOf(' ') > 0) {
				return new Piece(result.best().word(), Token.Kind.WORD, typed, source, result.best().score(),
						result.best().soundsAlike(), false, null);
			}
		}
		return null;
	}

	private void repairUnknown(Token token, List<Piece> out) {
		String text = token.text();
		String singular = singular(text);
		if (singular != null) {
			out.add(Piece.exact(singular, token.typed(), List.of(text)));
			return;
		}
		// "jiorecharge": a known phrase written without the space beats any spelling guess
		if (text.length() >= MIN_SPLIT_LENGTH && split(token, 2, true, out)) {
			return;
		}
		FuzzyMatcher.Result result = fuzzy.match(text);
		if (result.found()) {
			FuzzyMatcher.Match best = result.best();
			out.add(new Piece(best.word(), Token.Kind.WORD, token.typed(), List.of(text), best.score(),
					best.soundsAlike(), false, result.second()));
			return;
		}
		// "teasamosa": no spelling is close, but it is two known words run together
		if (text.length() >= MIN_SPLIT_LENGTH && split(token, 3, false, out)) {
			return;
		}
		out.add(new Piece(text, Token.Kind.WORD, token.typed(), List.of(text), 1.0, false, true, null));
	}

	/** "dosas" is "dosa", "boxes" is "box": only when the shorter form is known. */
	private String singular(String text) {
		if (text.length() >= 4 && text.endsWith("s")) {
			String stem = text.substring(0, text.length() - 1);
			if (lexicon.isWord(stem)) {
				return stem;
			}
			if (text.length() >= 5 && text.endsWith("es")) {
				stem = text.substring(0, text.length() - 2);
				if (lexicon.isWord(stem)) {
					return stem;
				}
			}
		}
		return null;
	}

	/**
	 * Splits one token into two known words.
	 * @param phraseOnly accept only a split whose halves form a known phrase
	 */
	private boolean split(Token token, int minHalf, boolean phraseOnly, List<Piece> out) {
		String text = token.text();
		int chosen = -1;
		for (int cut = minHalf; cut <= text.length() - minHalf; cut++) {
			String left = text.substring(0, cut);
			String right = text.substring(cut);
			if (!lexicon.isWord(left) || !lexicon.isWord(right)) {
				continue;
			}
			if (lexicon.isTerm(left + " " + right)) {
				chosen = cut;
				break;
			}
			if (!phraseOnly && chosen < 0 && (lexicon.isTerm(left) || lexicon.isTerm(right))) {
				chosen = cut;
			}
		}
		if (chosen < 0) {
			return false;
		}
		out.add(Piece.exact(text.substring(0, chosen), token.typed(), List.of(text)));
		out.add(Piece.exact(text.substring(chosen), token.typed(), List.of(text)));
		return true;
	}

}
