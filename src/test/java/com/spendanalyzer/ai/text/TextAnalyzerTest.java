package com.spendanalyzer.ai.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TextAnalyzerTest {

	private final TextAnalyzer analyzer = new TextAnalyzer(Set.of("for", "paid", "ku", "shop"),
			Map.of("tkt", List.of("ticket"), "bday", List.of("birthday")), Set.of("a4", "1mg", "7up")::contains);

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "JUICE!! | juice", "  Bus   Ticket  | bus ticket", "Juíce | juice",
			"ｊｕｉｃｅ | juice", "juuuuuice | juuice", "tea/coffee | tea coffee",
			"₹200 dosa | 200 dosa", "pen&pencil | pen pencil", "cofee...  | cofee", "१२ dosa | 12 dosa" })
	void normalizes(String raw, String expected) {
		assertThat(TextNormalizer.normalize(raw)).isEqualTo(expected);
	}

	@Test
	void normalizerCopesWithNothingAndWithOtherScripts() {
		assertThat(TextNormalizer.normalize("Domino's")).isEqualTo("dominos");
		assertThat(TextNormalizer.normalize(null)).isEmpty();
		assertThat(TextNormalizer.normalize("")).isEmpty();
		assertThat(TextNormalizer.normalize("???")).isEmpty();
		assertThat(TextNormalizer.normalize("🍕🍕")).isEmpty();
		// Tamil stays readable: letters and vowel signs are kept, punctuation goes
		assertThat(TextNormalizer.normalize("ஜூஸ்!")).isEqualTo("ஜூஸ்");
	}

	@Test
	void looksOnlyAtTheFirst120Characters() {
		String longText = "dosa ".repeat(4000);
		assertThat(TextNormalizer.normalize(longText).length()).isLessThanOrEqualTo(TextNormalizer.MAX_CHARS);
	}

	@Test
	void splitsLettersFromDigits() {
		assertThat(texts("dosa2")).containsExactly("dosa", "2");
		assertThat(texts("199rs recharge")).containsExactly("199", "rs", "recharge");
		assertThat(kinds("dosa 2")).containsExactly(Token.Kind.WORD, Token.Kind.NUMBER);
	}

	@Test
	void keepsKnownLetterDigitWordsWhole() {
		assertThat(texts("a4 sheet")).containsExactly("a4", "sheet");
		assertThat(texts("1mg order")).containsExactly("1mg", "order");
		assertThat(texts("b12 tablet")).containsExactly("b", "12", "tablet");
	}

	@Test
	void expandsShortFormsAndRemembersWhatWasTyped() {
		AnalyzedText text = analyzer.analyze("movie tkt");
		assertThat(text.tokens()).extracting(Token::text).containsExactly("movie", "ticket");
		assertThat(text.tokens().get(1).typed()).isEqualTo("tkt");
	}

	@Test
	void marksFillersAndDropsUnitsAfterNumbers() {
		assertThat(kinds("paid for juice")).containsExactly(Token.Kind.STOP, Token.Kind.STOP, Token.Kind.WORD);
		assertThat(texts("milk 500 ml")).containsExactly("milk", "500");
		assertThat(texts("2 x juice")).containsExactly("2", "juice");
		assertThat(texts("juice x2")).containsExactly("juice", "2");
		// "cup" is only a unit right after a number
		assertThat(texts("cup noodles")).containsExactly("cup", "noodles");
	}

	@Test
	void contentKeyAndItemLeaveOutNumbersAndFillers() {
		AnalyzedText text = analyzer.analyze("Paid for JUSE 2 shop");
		assertThat(text.contentWords()).containsExactly("juse");
		assertThat(text.memoryKey()).isEqualTo("juse");
		assertThat(text.cleanedItem()).isEqualTo("juse");
		assertThat(text.hasContent()).isTrue();
	}

	@Test
	void textWithoutWordsHasNoContentButStillAKeyAndAnItem() {
		AnalyzedText number = analyzer.analyze("200");
		assertThat(number.hasContent()).isFalse();
		assertThat(number.memoryKey()).isEqualTo("200");
		assertThat(number.cleanedItem()).isEqualTo(AnalyzedText.UNKNOWN_ITEM);

		AnalyzedText filler = analyzer.analyze("paid");
		assertThat(filler.hasContent()).isFalse();
		assertThat(filler.cleanedItem()).isEqualTo("paid");

		assertThat(analyzer.analyze("???").tokens()).isEmpty();
		assertThat(analyzer.analyze(null).cleanedItem()).isEqualTo(AnalyzedText.UNKNOWN_ITEM);
	}

	@Test
	void capsTokensAndTokenLength() {
		AnalyzedText many = analyzer.analyze("a b c d e f g h i j k l m n o p q r s t");
		assertThat(many.tokens()).hasSize(TextAnalyzer.MAX_TOKENS);

		AnalyzedText longWord = analyzer.analyze("x".repeat(3) + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz");
		assertThat(longWord.tokens()).hasSize(1);
		assertThat(longWord.tokens().get(0).text().length()).isEqualTo(TextAnalyzer.MAX_TOKEN_CHARS);
	}

	@Test
	void itemIsCutAtSixtyCharacters() {
		AnalyzedText text = analyzer.analyze("abcdefghij ".repeat(11));
		assertThat(text.cleanedItem().length()).isLessThanOrEqualTo(AnalyzedText.MAX_ITEM_CHARS);
		assertThat(text.cleanedItem()).doesNotEndWith(" ");
	}

	private List<String> texts(String raw) {
		return analyzer.analyze(raw).tokens().stream().map(Token::text).toList();
	}

	private List<Token.Kind> kinds(String raw) {
		return analyzer.analyze(raw).tokens().stream().map(Token::kind).toList();
	}

}
