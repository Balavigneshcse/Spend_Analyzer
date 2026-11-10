package com.spendanalyzer.ai.fuzzy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.spendanalyzer.ai.lexicon.Knowledge;
import com.spendanalyzer.ai.model.TypoAugmenter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Sound key, weighted edit distance, and the matcher built from both. */
class FuzzyTest {

	// ------------------------------------------------------------------ sound key

	@ParameterizedTest
	@CsvSource({ "juse, juice, JUS", "cofee, coffee, KUFI", "chapathi, chapati, CAPATI", "tiffen, tiffin, TIFIN",
			"zerox, xerox, JIRUKS", "fone, phone, FUN", "dhosa, dosa, DUSA", "shampu, shampoo, SAMPU",
			"biskut, biscut, BISKUT", "chaya, chaia, CI" })
	void wordsThatSoundAlikeShareTheFullKey(String typed, String known, String key) {
		assertThat(PhoneticKey.full(typed)).isEqualTo(key);
		assertThat(PhoneticKey.full(known)).isEqualTo(key);
	}

	@ParameterizedTest
	@CsvSource({ "briyany, biryani, BRN", "briyani, biriyani, BRN", "tkt, ticket, TKT", "rchrg, recharge, RCRG",
			"petrl, petrol, PTRL", "pzza, pizza, PJ" })
	void skeletonKeyIgnoresVowels(String typed, String known, String skeleton) {
		assertThat(PhoneticKey.skeleton(PhoneticKey.full(typed))).isEqualTo(skeleton);
		assertThat(PhoneticKey.skeleton(PhoneticKey.full(known))).isEqualTo(skeleton);
	}

	@ParameterizedTest
	@CsvSource({ "pen, pan", "bus, bun", "cake, coke", "tea, toy", "milk, silk" })
	void differentWordsKeepDifferentFullKeys(String one, String other) {
		assertThat(PhoneticKey.full(one)).isNotEqualTo(PhoneticKey.full(other));
	}

	// ------------------------------------------------------------------ edit distance

	@ParameterizedTest
	@CsvSource({
			// vowel inserted (0.5) + c/s sound-alike (0.5)
			"juse, juice, 1.0",
			// r and i swapped (0.7) + y for i (0.4)
			"briyany, biryani, 1.1",
			// one vowel for another
			"tiffen, tiffin, 0.4",
			// a doubled letter typed once
			"cofee, coffee, 0.2",
			// an h after a consonant
			"chapathi, chapati, 0.2",
			// two letters swapped
			"pertol, petrol, 0.7",
			// a wrong consonant
			"bus, bun, 1.0",
			// a wrong first letter costs extra
			"mus, bus, 1.5",
			// sound-alike first letters do not
			"zerox, xerox, 0.5", "same, same, 0.0" })
	void costsWhatTheSlipIsWorth(String typed, String known, double cost) {
		assertThat(WeightedEditDistance.distance(typed, known)).isCloseTo(cost, within(1e-9));
		assertThat(WeightedEditDistance.distance(known, typed)).isCloseTo(cost, within(1e-9));
	}

	@Test
	void similarityIsOneForEqualAndFallsWithDistance() {
		assertThat(WeightedEditDistance.similarity("dosa", "dosa")).isEqualTo(1.0);
		assertThat(WeightedEditDistance.similarity("juse", "juice")).isCloseTo(0.8, within(1e-9));
		assertThat(WeightedEditDistance.similarity("soap", "milk")).isLessThan(0.5);
		assertThat(WeightedEditDistance.similarity("", "")).isEqualTo(1.0);
		assertThat(WeightedEditDistance.similarity("abc", "")).isEqualTo(0.0);
	}

	// ------------------------------------------------------------------ matcher

	private static final Set<String> WORDS = Set.of("juice", "biryani", "briyani", "coffee", "tiffin", "petrol", "tea",
			"soap", "soup", "cake", "coke", "pen", "paan", "bus", "bun", "ticket", "recharge", "room", "milk",
			"music", "ice");

	private final FuzzyMatcher matcher = new FuzzyMatcher(WORDS, WORDS);

	@ParameterizedTest
	@CsvSource({ "juse, juice", "briyany, briyani", "bryani, briyani", "cofee, coffee", "tiffen, tiffin",
			"pertol, petrol", "tee, tea", "tiket, ticket", "rechrge, recharge", "rom, room", "mlik, milk" })
	void repairsMisspellings(String typed, String expected) {
		FuzzyMatcher.Result result = matcher.match(typed);
		assertThat(result.found()).as(typed).isTrue();
		assertThat(result.best().word()).isEqualTo(expected);
		assertThat(result.best().score()).isBetween(FuzzyMatcher.threshold(typed.length()), FuzzyMatcher.MAX_SCORE);
	}

	@Test
	void theUsersExamplesScoreAsDesigned() {
		// 1 - 1.0/5 = 0.80, plus both sound keys equal
		assertThat(matcher.match("juse").best().score()).isCloseTo(0.88, within(1e-9));
		assertThat(matcher.match("juse").best().soundsAlike()).isTrue();
		// "briyany" is one vowel away from the listed spelling "briyani"
		assertThat(matcher.match("briyany").best().score()).isGreaterThan(0.9);
	}

	@ParameterizedTest
	@CsvSource({ "asdf", "qwerty", "xyz", "zzzz", "pq", "a", "blorf", "pon", "ban" })
	void leavesNoiseAndVeryShortWordsAlone(String typed) {
		assertThat(matcher.match(typed).found()).as(typed).isFalse();
	}

	@Test
	void threeLetterWordsMustSoundTheSame() {
		// "tee" sounds like "tea"; "pon" merely looks like "pen"
		assertThat(matcher.match("tee").found()).isTrue();
		assertThat(matcher.match("pon").found()).isFalse();
		assertThat(matcher.match("bas").found()).isFalse();
	}

	@Test
	void reportsACloseRunnerUp() {
		// equally far from "soap" and "soup": the caller must not trust either blindly
		FuzzyMatcher.Result result = matcher.match("soep");
		assertThat(result.found()).isTrue();
		assertThat(result.second()).isNotNull();
		assertThat(Set.of(result.best().word(), result.second().word())).containsExactlyInAnyOrder("soap", "soup");
	}

	@Test
	void handlesNullEmptyAndOddInput() {
		assertThat(matcher.match(null).found()).isFalse();
		assertThat(matcher.match("").found()).isFalse();
		assertThat(matcher.match("ஜூஸ்").found()).isFalse();
		assertThat(matcher.match("x".repeat(500)).found()).isFalse();
	}

	@Test
	void findsTheRightWordAmongTheWholeVocabulary() {
		// every lexicon word of 6+ letters, damaged once by the typo generator, must come back
		Knowledge knowledge = Knowledge.load();
		List<String> words = new ArrayList<>();
		for (String word : new java.util.TreeSet<>(knowledge.lexicon().standaloneWords())) {
			if (word.length() >= 6 && word.chars().allMatch(c -> c >= 'a' && c <= 'z')) {
				words.add(word);
			}
		}
		FuzzyMatcher full = new FuzzyMatcher(knowledge.lexicon().words(), knowledge.lexicon().writtenWords());
		Random random = new Random(7);
		int tried = 0;
		int recovered = 0;
		for (String word : words) {
			String typo = TypoAugmenter.misspell(word, random);
			if (typo.equals(word) || knowledge.lexicon().isWord(typo)) {
				continue;
			}
			tried++;
			FuzzyMatcher.Result result = full.match(typo);
			if (result.found() && (result.best().word().equals(word)
					|| (result.second() != null && result.second().word().equals(word)))) {
				recovered++;
			}
		}
		double recall = recovered / (double) tried;
		System.out.printf("fuzzy recall on %d single-slip typos of long lexicon words: %.3f%n", tried, recall);
		assertThat(tried).isGreaterThan(1000);
		assertThat(recall).isGreaterThanOrEqualTo(0.90);
	}

}
