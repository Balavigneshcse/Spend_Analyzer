package com.spendanalyzer.ai.lexicon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.spendanalyzer.ai.fuzzy.FuzzyMatcher;
import com.spendanalyzer.ai.lexicon.LexiconMatcher.Span;
import com.spendanalyzer.ai.text.TextAnalyzer;
import com.spendanalyzer.model.Category;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The hand-written word lists, and how phrases are found in a text. */
class LexiconTest {

	private static Knowledge knowledge;

	private static TextAnalyzer analyzer;

	private static LexiconMatcher matcher;

	@BeforeAll
	static void load() {
		knowledge = Knowledge.load();
		Lexicon lexicon = knowledge.lexicon();
		analyzer = new TextAnalyzer(knowledge.stopWords(), knowledge.aliases(), lexicon::isWord);
		matcher = new LexiconMatcher(lexicon, new FuzzyMatcher(lexicon.words(), lexicon.writtenWords()));
	}

	// ------------------------------------------------------------------ the data

	@Test
	void hasAtLeast900ItemsAndManySpellings() {
		Lexicon lexicon = knowledge.lexicon();
		System.out.printf("lexicon: %d items, %d spellings and phrases, %d distinct words%n", lexicon.canonicalCount(),
				lexicon.termCount(), lexicon.words().size());
		assertThat(lexicon.canonicalCount()).isGreaterThanOrEqualTo(900);
		assertThat(lexicon.entries().size()).isGreaterThanOrEqualTo(3 * 900);
	}

	@Test
	void everyCategoryExceptOtherIsCovered() {
		for (Category category : Category.values()) {
			long items = knowledge.lexicon().canonicalCount(category);
			if (category == Category.OTHER) {
				assertThat(items).isZero();
			}
			else {
				assertThat(items).as(category.name()).isGreaterThanOrEqualTo(40);
				assertThat(knowledge.amountRanges()).containsKey(category);
			}
		}
	}

	@Test
	void fewTermsSitInTwoCategories() {
		Map<String, List<String>> owners = new TreeMap<>();
		for (LexiconEntry entry : knowledge.lexicon().entries()) {
			owners.computeIfAbsent(entry.term(), k -> new ArrayList<>()).add(entry.category().name());
		}
		List<String> shared = owners.entrySet()
			.stream()
			.filter(e -> e.getValue().size() > 1)
			.map(e -> e.getKey() + " " + e.getValue())
			.toList();
		// allowed (the engine copes), but each one is a decision, not an accident: keep them rare
		assertThat(shared).as("terms listed in more than one category").hasSizeLessThanOrEqualTo(10);
	}

	@Test
	void itemNamesFitTheItemColumn() {
		for (LexiconEntry entry : knowledge.lexicon().entries()) {
			assertThat(entry.canonical().length()).as(entry.canonical()).isBetween(2, 60);
			assertThat(entry.tokenCount()).as(entry.term()).isLessThanOrEqualTo(Lexicon.MAX_PHRASE_TOKENS);
		}
	}

	@Test
	void fillersNeverShadowAnItem() {
		for (String word : knowledge.stopWords()) {
			assertThat(knowledge.lexicon().isTerm(word)).as("filler that is also an item: " + word).isFalse();
		}
		assertThat(knowledge.stopWords()).contains("paid", "misc", "for", "ku", "ka", "kadai");
	}

	@ParameterizedTest
	@CsvSource({ "juice, juice, SNACKS_BEVERAGES", "chai, tea, SNACKS_BEVERAGES", "kaapi, coffee, SNACKS_BEVERAGES",
			"briyani, biryani, FOOD_MEALS", "sappadu, meals, FOOD_MEALS", "paal, milk, GROCERIES",
			"muttai, eggs, GROCERIES", "sabzi, vegetables, GROCERIES", "share auto, share auto, TRANSPORT_FUEL",
			"tnstc, tnstc, TRANSPORT_FUEL", "zerox, xerox, EDUCATION_STATIONERY", "vaadagai, room rent, RENT_UTILITIES",
			"current bill, eb bill, RENT_UTILITIES", "jio, jio recharge, MOBILE_INTERNET",
			"marundhu, medicine, HEALTH_MEDICINE", "saloon, salon, PERSONAL_CARE", "meesho, meesho, SHOPPING",
			"bookmyshow, bookmyshow, ENTERTAINMENT_SUBS", "moi, wedding gift, SOCIAL_GIFTS",
			"kadan, lent to friend, TRANSFERS_FEES" })
	void knowsStudentWordsIncludingTanglishAndHinglish(String term, String item, Category category) {
		List<LexiconEntry> found = knowledge.lexicon().find(term);
		assertThat(found).as(term).isNotEmpty();
		assertThat(found.get(0).canonical()).isEqualTo(item);
		assertThat(found.get(0).category()).isEqualTo(category);
	}

	@Test
	void phrasesAlsoMatchWhenWrittenTogether() {
		assertThat(knowledge.lexicon().find("busticket")).isNotEmpty();
		assertThat(knowledge.lexicon().find("busticket").get(0).derived()).isTrue();
		assertThat(knowledge.lexicon().find("roomrent").get(0).canonical()).isEqualTo("room rent");
	}

	// ------------------------------------------------------------------ matching

	@ParameterizedTest
	@CsvSource({
			// the longest phrase wins over its parts
			"bus ticket, bus ticket", "amazon prime, amazon prime", "ice cream, ice cream",
			// short forms and joined or split writing
			"bus tkt, bus ticket", "busticket, bus ticket", "note book, notebook", "pen drive, pendrive",
			"jiorecharge, jio recharge",
			// plural, misspelt word inside a phrase, known spelling inside a phrase
			"dosas, dosa", "bus tiket, bus ticket", "chiken rice, chicken fried rice", "mess bil, mess bill",
			// a misspelt word is repaired before it is cut in two; a misspelt phrase made of real words
			"vegtable, vegetables", "pani pori, pani puri", "ebbill, eb bill",
			// spelling and sound repair
			"juse, juice", "briyany, biryani", "pertol, petrol", "cofee, coffee" })
	void findsTheItem(String typed, String item) {
		LexiconMatcher.Result result = matcher.match(analyzer.analyze(typed).tokens());
		assertThat(result.spans()).as(typed).hasSize(1);
		assertThat(result.spans().get(0).top().canonical()).isEqualTo(item);
		assertThat(result.unknownWords()).isEmpty();
	}

	@Test
	void aWordTypedApartWithALetterMissingIsReadAsThatWord() {
		// "har" is no word at all, and "haircut" is also listed as "hair cut"
		Span span = only("har cut");
		assertThat(span.top().canonical()).isEqualTo("haircut");
		assertThat(span.exact()).isFalse();
		assertThat(span.typed()).isEqualTo("har cut");
		assertThat(span.sourceWords()).containsExactly("har", "cut");
		// only a very close match counts: an unknown word next to "cut" is not a haircut
		assertThat(matcher.match(analyzer.analyze("zqx cut").tokens()).spans())
			.extracting(s -> s.top().canonical())
			.doesNotContain("haircut");
	}

	@Test
	void exactHitsAreExactAndRepairsAreNot() {
		assertThat(only("juice").exact()).isTrue();
		assertThat(only("chai").exact()).isTrue();
		Span repaired = only("juse");
		assertThat(repaired.exact()).isFalse();
		assertThat(repaired.soundsAlike()).isTrue();
		assertThat(repaired.typed()).isEqualTo("juse");
		assertThat(repaired.similarity()).isBetween(0.8, 0.99);
	}

	@Test
	void aKnownWordIsNeverRepairedIntoAnother() {
		assertThat(only("soap").top().canonical()).isEqualTo("soap");
		assertThat(only("soup").top().canonical()).isEqualTo("soup");
		assertThat(only("cake").top().canonical()).isEqualTo("cake");
		assertThat(only("coke").top().canonical()).isEqualTo("coke");
		assertThat(only("pen").top().category()).isEqualTo(Category.EDUCATION_STATIONERY);
		assertThat(only("paan").top().category()).isEqualTo(Category.SNACKS_BEVERAGES);
		assertThat(only("bus").top().category()).isEqualTo(Category.TRANSPORT_FUEL);
		assertThat(only("bun").top().category()).isEqualTo(Category.SNACKS_BEVERAGES);
	}

	@Test
	void findsSeveralItemsAndReportsUnknownWords() {
		LexiconMatcher.Result result = matcher.match(analyzer.analyze("amazon earphone zqxv").tokens());
		assertThat(result.spans()).extracting(span -> span.top().canonical()).containsExactly("amazon", "earphone");
		assertThat(result.unknownWords()).containsExactly("zqxv");
		assertThat(result.matchedWords()).containsExactly("amazon", "earphone");
	}

	@Test
	void twoKnownWordsRunTogetherAreSplit() {
		LexiconMatcher.Result result = matcher.match(analyzer.analyze("milkeggs").tokens());
		assertThat(result.spans()).extracting(span -> span.top().canonical()).containsExactly("milk", "eggs");
		assertThat(result.spans()).allMatch(Span::exact);
	}

	@Test
	void fillersAndNumbersAreNotItems() {
		assertThat(matcher.match(analyzer.analyze("paid 200 for").tokens()).spans()).isEmpty();
		assertThat(matcher.match(analyzer.analyze("").tokens()).spans()).isEmpty();
	}

	private static Span only(String typed) {
		List<Span> spans = matcher.match(analyzer.analyze(typed).tokens()).spans();
		assertThat(spans).as(typed).hasSize(1);
		return spans.get(0);
	}

}
