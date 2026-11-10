package com.spendanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.spendanalyzer.ai.api.Prediction;
import com.spendanalyzer.ai.api.PredictionSource;
import com.spendanalyzer.ai.engine.CategorizerEngine;
import com.spendanalyzer.ai.learn.UserMemoryStore;
import com.spendanalyzer.model.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** What the product promises, checked on the plain engine: no Spring, no database. */
class CategorizerAcceptanceTest {

	private final CategorizerEngine engine = TestEngines.full();

	private Prediction predict(String text) {
		return engine.categorize(null, text, null);
	}

	// ------------------------------------------------------------------ the user's own examples

	@ParameterizedTest
	@CsvSource({ "juse, SNACKS_BEVERAGES, juice", "briyany, FOOD_MEALS, biryani", "pen, EDUCATION_STATIONERY, pen",
			"bus ticket, TRANSPORT_FUEL, bus ticket" })
	void theFourExamplesFromTheBrief(String text, Category category, String item) {
		Prediction prediction = predict(text);
		assertThat(prediction.category()).isEqualTo(category);
		assertThat(prediction.item()).isEqualTo(item);
		assertThat(prediction.confidence()).isGreaterThanOrEqualTo(0.80);
		assertThat(prediction.needsReview()).isFalse();
		assertThat(prediction.explanation()).isNotBlank().doesNotContain("<");
	}

	@Test
	void explainsASoundAlikeTheWayTheContractShows() {
		Prediction prediction = predict("juse");
		assertThat(prediction.explanation()).isEqualTo("\"juse\" sounds like \"juice\".");
		assertThat(prediction.source()).isEqualTo(PredictionSource.LEXICON_FUZZY);
		assertThat(predict("briyany").explanation()).isEqualTo("\"briyany\" sounds like \"biryani\".");
		assertThat(predict("pen").source()).isEqualTo(PredictionSource.LEXICON_EXACT);
	}

	@ParameterizedTest
	@CsvSource({ "chai, SNACKS_BEVERAGES, tea", "auto, TRANSPORT_FUEL, auto", "petrol, TRANSPORT_FUEL, petrol",
			"recharge 199, MOBILE_INTERNET, recharge", "dosa 2, FOOD_MEALS, dosa", "xerox, EDUCATION_STATIONERY, xerox",
			"movie tkt, ENTERTAINMENT_SUBS, movie ticket", "amazon earphone, SHOPPING, earphone",
			"room rent, RENT_UTILITIES, room rent", "eb bill, RENT_UTILITIES, eb bill",
			"medicine, HEALTH_MEDICINE, medicine", "haircut, PERSONAL_CARE, haircut", "cofee, SNACKS_BEVERAGES, coffee",
			"tiffen, FOOD_MEALS, tiffin", "chapathi, FOOD_MEALS, chapati", "share auto, TRANSPORT_FUEL, share auto",
			"swiggy, FOOD_MEALS, swiggy", "jio recharge, MOBILE_INTERNET, jio recharge",
			"bus tkt, TRANSPORT_FUEL, bus ticket", "dosa2, FOOD_MEALS, dosa", "BUS TICKET!!, TRANSPORT_FUEL, bus ticket",
			"paid for juice, SNACKS_BEVERAGES, juice", "2 x dosa, FOOD_MEALS, dosa" })
	void everydayStudentSpends(String text, Category category, String item) {
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(category);
		assertThat(prediction.item()).as(text).isEqualTo(item);
		assertThat(prediction.confidence()).as(text).isGreaterThanOrEqualTo(0.80);
		assertThat(prediction.needsReview()).as(text).isFalse();
	}

	@ParameterizedTest
	@CsvSource({ "dosa 2, dosa", "dosa 40, dosa", "3 dosa, dosa", "milk 500 ml, milk", "recharge 239, recharge",
			"juice 2 nos, juice" })
	void quantitiesAndAmountsAreNotPartOfTheItem(String text, String item) {
		assertThat(predict(text).item()).isEqualTo(item);
	}

	// ------------------------------------------------------------------ confusable words

	@ParameterizedTest
	@CsvSource({ "soap, PERSONAL_CARE", "soup, FOOD_MEALS", "cake, SNACKS_BEVERAGES", "coke, SNACKS_BEVERAGES",
			"pen, EDUCATION_STATIONERY", "pan, SNACKS_BEVERAGES", "paan, SNACKS_BEVERAGES", "bus, TRANSPORT_FUEL",
			"bun, SNACKS_BEVERAGES" })
	void confusableWordsStayApart(String text, Category category) {
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(category);
		assertThat(prediction.source()).as(text).isEqualTo(PredictionSource.LEXICON_EXACT);
		assertThat(prediction.needsReview()).as(text).isFalse();
	}

	@Test
	void confusablePairsKeepTheirOwnItems() {
		assertThat(predict("soap").item()).isEqualTo("soap");
		assertThat(predict("soup").item()).isEqualTo("soup");
		assertThat(predict("cake").item()).isEqualTo("cake");
		assertThat(predict("coke").item()).isEqualTo("coke");
		assertThat(predict("pen").item()).isEqualTo("pen");
		assertThat(predict("paan").item()).isEqualTo("paan");
		assertThat(predict("bun").item()).isEqualTo("bun");
	}

	@ParameterizedTest
	@ValueSource(strings = { "pon", "ban", "bas" })
	void aShortWordOneLetterOffIsNotSilentlyTurnedIntoAnother(String text) {
		// "pon" could be pen, pan or pin: never accept such a guess without asking
		assertThat(predict(text).needsReview()).as(text).isTrue();
	}

	// ------------------------------------------------------------------ junk

	@ParameterizedTest
	@ValueSource(strings = { "asdf", "misc", "paid", "200", "", "???", "qwerty", "zzzz", "aaaa", "   ", "12 34", "." })
	void junkGoesToOtherAndNeedsReview(String text) {
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(Category.OTHER);
		assertThat(prediction.needsReview()).as(text).isTrue();
		assertThat(prediction.source()).as(text).isEqualTo(PredictionSource.ABSTAIN);
		assertThat(prediction.confidence()).as(text).isLessThan(CategorizerEngine.REVIEW);
		assertThat(prediction.item()).as(text).isNotBlank();
	}

	@Test
	void nullTextIsJunkToo() {
		Prediction prediction = predict(null);
		assertThat(prediction.category()).isEqualTo(Category.OTHER);
		assertThat(prediction.needsReview()).isTrue();
		assertThat(prediction.item()).isEqualTo("unknown");
	}

	@ParameterizedTest
	@ValueSource(strings = { "ravi", "gpay to ravi", "paid priya", "amma", "for karthik 200", "salary" })
	void aPersonOrIncomeIsNotAnItem(String text) {
		// "ravi" is one letter from "rava": names must never be repaired into groceries
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(Category.OTHER);
		assertThat(prediction.needsReview()).as(text).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "gpay to gopi 500", "phonepe to gopi", "paid 200 to gopi", "paytm to zubair 50",
			"upi to gopi anna" })
	void whoWasPaidIsNotAnItemEvenWhenTheNameIsNotInAnyList(String text) {
		// "gopi" is one letter from "gobi" (cauliflower); after "gpay to" it names a person or a shop
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(Category.OTHER);
		assertThat(prediction.needsReview()).as(text).isTrue();
		assertThat(prediction.item()).as(text).doesNotContain("cauliflower");
		// the same before the model is trained
		Prediction cold = CategorizerEngine.create(UserMemoryStore.NONE).categorize(null, text, null);
		assertThat(cold.category()).as(text).isEqualTo(Category.OTHER);
		assertThat(cold.needsReview()).as(text).isTrue();
	}

	@Test
	void aKnownWordAfterToIsStillWhatWasBought() {
		assertThat(predict("paytm to canteen").category()).isEqualTo(Category.FOOD_MEALS);
		assertThat(predict("gpay to gobi").item()).isEqualTo("cauliflower");
		// money that went to a person is a transfer when the text says so
		Prediction lent = predict("lent 500 to gopi");
		assertThat(lent.category()).isEqualTo(Category.TRANSFERS_FEES);
		assertThat(lent.item()).isEqualTo("lent to friend");
		assertThat(lent.needsReview()).isFalse();
	}

	// ------------------------------------------------------------------ phrases made of fillers

	@ParameterizedTest
	@CsvSource({ "credit card payment 2000, TRANSFERS_FEES, credit card bill", "card payment, TRANSFERS_FEES, credit card bill",
			"took cash, TRANSFERS_FEES, cash withdrawal", "double payment, TRANSFERS_FEES, lost money",
			"money to home, TRANSFERS_FEES, sent home", "friend ku kaasu, TRANSFERS_FEES, lent to friend",
			"dost ko diya, TRANSFERS_FEES, lent to friend", "online class, EDUCATION_STATIONERY, online course",
			"special class, EDUCATION_STATIONERY, tuition", "paid for friends, SOCIAL_GIFTS, paid for friends",
			"paid for friend, SOCIAL_GIFTS, paid for friends", "card for friend, SOCIAL_GIFTS, greeting card",
			"for mom, SOCIAL_GIFTS, pocket money given", "for dad, SOCIAL_GIFTS, pocket money given",
			"amma ku, SOCIAL_GIFTS, pocket money given", "appa ku, SOCIAL_GIFTS, pocket money given",
			"thambi ku, SOCIAL_GIFTS, pocket money given", "thangachi ku, SOCIAL_GIFTS, pocket money given",
			"paati ku 100, SOCIAL_GIFTS, pocket money given" })
	void aKnownPhraseMadeOnlyOfFillersIsAnAnswer(String text, Category category, String item) {
		// every word here is a filler on its own ("credit", "card", "payment"); together they are an item
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(category);
		assertThat(prediction.item()).as(text).isEqualTo(item);
		assertThat(prediction.source()).as(text).isEqualTo(PredictionSource.LEXICON_EXACT);
		assertThat(prediction.confidence()).as(text).isGreaterThanOrEqualTo(0.80);
		assertThat(prediction.needsReview()).as(text).isFalse();
		assertThat(engine.rank(text, null).top()).as(text).isEqualTo(category);
		// no meaningful word for the model to read: the answer is the same without it
		assertThat(CategorizerEngine.create(UserMemoryStore.NONE).categorize(null, text, null)).as(text)
			.isEqualTo(prediction);
	}

	@ParameterizedTest
	@CsvSource({ "room expenses, RENT_UTILITIES", "room items, GROCERIES", "stuff for room, GROCERIES",
			"cash to friend, TRANSFERS_FEES", "fresh to home, GROCERIES" })
	void aVaguePhraseMadeOnlyOfFillersGivesItsCategoryAndAsksForReview(String text, Category category) {
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(category);
		assertThat(prediction.needsReview()).as(text).isTrue();
		assertThat(prediction.confidence()).as(text).isLessThan(CategorizerEngine.ACCEPT);
	}

	@ParameterizedTest
	@ValueSource(strings = { "credit", "card", "payment", "online", "class", "for", "mom", "ku", "paid for", "cash",
			"credit payment", "pack", "2 pack", "maalai" })
	void fillersThatAreNotAKnownPhraseStillSayNothing(String text) {
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(Category.OTHER);
		assertThat(prediction.source()).as(text).isEqualTo(PredictionSource.ABSTAIN);
		assertThat(engine.rank(text, null).ranked()).as(text).isEmpty();
	}

	// ------------------------------------------------------------------ reviewed word-list entries

	@ParameterizedTest
	@CsvSource({ "28 days pack, unlimited pack", "56 days pack, unlimited pack", "84 days pack, unlimited pack",
			"365 days pack, unlimited pack", "data pack, data pack", "net pack, data pack", "jio pack, jio recharge",
			"recharge pack, recharge" })
	void aRechargePackIsStillARecharge(String text, String item) {
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(Category.MOBILE_INTERNET);
		assertThat(prediction.item()).as(text).isEqualTo(item);
		assertThat(prediction.needsReview()).as(text).isFalse();
	}

	@ParameterizedTest
	@CsvSource({ "chips pack, SNACKS_BEVERAGES, chips", "milk pack, GROCERIES, milk", "biscuit pack 2, SNACKS_BEVERAGES, biscuit",
			"maalai tea, SNACKS_BEVERAGES, tea", "kaalai dosa, FOOD_MEALS, dosa" })
	void packAndTimeOfDayAreFillersNotItems(String text, Category category, String item) {
		// "pack" used to count as a mobile plan and "maalai" (evening) as a garland
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(category);
		assertThat(prediction.item()).as(text).isEqualTo(item);
		assertThat(prediction.source()).as(text).isEqualTo(PredictionSource.LEXICON_EXACT);
		assertThat(prediction.needsReview()).as(text).isFalse();
		assertThat(prediction.alternatives()).as(text)
			.noneMatch(a -> a.category() == Category.MOBILE_INTERNET && a.confidence() > 0.05);
	}

	@Test
	void aDocIsADocumentNotADoctor() {
		for (String text : List.of("doc print", "doc xerox")) {
			Prediction prediction = predict(text);
			assertThat(prediction.category()).as(text).isEqualTo(Category.EDUCATION_STATIONERY);
			assertThat(prediction.item()).as(text).doesNotContain("doctor");
		}
		// the usual short form for a doctor still works
		assertThat(predict("dr fee").category()).isEqualTo(Category.HEALTH_MEDICINE);
	}

	@ParameterizedTest
	@CsvSource({ "gate exam fee, EDUCATION_STATIONERY, competitive exam form",
			"gate application, EDUCATION_STATIONERY, competitive exam form", "hindi class, EDUCATION_STATIONERY, language class",
			"lab coat, EDUCATION_STATIONERY, lab coat", "apron, EDUCATION_STATIONERY, lab coat",
			"mini drafter, EDUCATION_STATIONERY, drafter", "printer ink, SHOPPING, printer",
			"ink cartridge, SHOPPING, printer", "new laptop, SHOPPING, laptop",
			"assignment, EDUCATION_STATIONERY, assignment", "assignment print, EDUCATION_STATIONERY, assignment" })
	void theItemNameSaysWhatWasBought(String text, Category category, String item) {
		// repeated buys are grouped by this name: a lab coat must not be counted as a drafter
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(category);
		assertThat(prediction.item()).as(text).isEqualTo(item);
		assertThat(prediction.needsReview()).as(text).isFalse();
	}

	// ------------------------------------------------------------------ gaps a blind test found

	@ParameterizedTest
	@CsvSource({
			// a fine buys nothing, whatever it was for
			"traffic fine, TRANSFERS_FEES, traffic fine", "helmet fine 500, TRANSFERS_FEES, traffic fine",
			"challan, TRANSFERS_FEES, traffic fine", "no parking fine, TRANSFERS_FEES, traffic fine",
			// buying a helmet or paying for parking is still transport
			"helmet, TRANSPORT_FUEL, helmet", "parking ticket, TRANSPORT_FUEL, parking",
			// "gaadi" is a vehicle, like "vandi"
			"gaadi service, TRANSPORT_FUEL, bike service", "gaadi repair, TRANSPORT_FUEL, bike service",
			"gaadi petrol, TRANSPORT_FUEL, petrol",
			// getting clothes ironed is laundry, not buying clothes
			"dress iron 5 nos, PERSONAL_CARE, ironing", "shirt ironing, PERSONAL_CARE, ironing",
			"uniform iron, PERSONAL_CARE, ironing", "dress, SHOPPING, dress",
			// money sent to the family is a transfer
			"amma ku anupinen, TRANSFERS_FEES, sent home", "appa ku anupinen 2000, TRANSFERS_FEES, sent home",
			"veetuku anupinen, TRANSFERS_FEES, sent home" })
	void knowledgeGapsFoundByTheBlindTest(String text, Category category, String item) {
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(category);
		assertThat(prediction.item()).as(text).isEqualTo(item);
		assertThat(prediction.confidence()).as(text).isGreaterThanOrEqualTo(0.80);
		assertThat(prediction.needsReview()).as(text).isFalse();
	}

	@Test
	void aWordTypedApartWithALetterMissingIsStillFound() {
		// "har" is no word at all, so "har cut" is read as one misspelt word: haircut
		Prediction prediction = predict("har cut");
		assertThat(prediction.category()).isEqualTo(Category.PERSONAL_CARE);
		assertThat(prediction.item()).isEqualTo("haircut");
		assertThat(prediction.source()).isEqualTo(PredictionSource.LEXICON_FUZZY);
	}

	// ------------------------------------------------------------------ several words

	@ParameterizedTest
	@CsvSource({
			// a shop and what was bought there: the item decides the category and names the item
			"amazon earphone, SHOPPING, earphone", "swiggy biryani, FOOD_MEALS, biryani",
			"dmart milk, GROCERIES, milk", "medplus tablets, HEALTH_MEDICINE, medicine",
			// a phrase wins over its words: "prime" here is a subscription, not shopping at amazon
			"amazon prime, ENTERTAINMENT_SUBS, amazon prime", "mobile recharge, MOBILE_INTERNET, recharge",
			"metro card recharge, TRANSPORT_FUEL, metro card", "mess bill, FOOD_MEALS, mess bill",
			"hostel fee, RENT_UTILITIES, hostel fee", "exam fee, EDUCATION_STATIONERY, exam fee",
			// who it was for does not change what it was
			"lent 500 to ravi, TRANSFERS_FEES, lent to friend", "juice for priya, SNACKS_BEVERAGES, juice" })
	void phrasesAndContext(String text, Category category, String item) {
		Prediction prediction = predict(text);
		assertThat(prediction.category()).as(text).isEqualTo(category);
		assertThat(prediction.item()).as(text).isEqualTo(item);
		assertThat(prediction.needsReview()).as(text).isFalse();
	}

	@Test
	void whenKnownWordsDisagreeTheModelSettlesIt() {
		// "idli" is a meal and "vada" a snack: together they are breakfast
		Prediction breakfast = predict("idli 3 vada 1");
		assertThat(breakfast.category()).isEqualTo(Category.FOOD_MEALS);
		assertThat(breakfast.source()).isEqualTo(PredictionSource.ENSEMBLE);
		assertThat(breakfast.item()).isEqualTo("idli");
		// the word lists alone cannot decide this one
		assertThat(TestEngines.lexiconOnly().categorize(null, "idli 3 vada 1", null).category())
			.isEqualTo(Category.OTHER);
	}

	@Test
	void aVagueWordAloneAsksForReview() {
		for (String vague : List.of("ticket", "bill")) {
			assertThat(predict(vague).needsReview()).as(vague).isTrue();
		}
		// the price is a hint for a vague word: a 30 rupee ticket is a bus, not a film
		assertThat(engine.categorize(null, "ticket", new BigDecimal("30")).category())
			.isEqualTo(Category.TRANSPORT_FUEL);
	}

	@Test
	void anUnknownButWordLikeTextGetsAGuessThatAlwaysNeedsReview() {
		Prediction prediction = predict("exam");
		assertThat(prediction.category()).isEqualTo(Category.EDUCATION_STATIONERY);
		assertThat(prediction.source()).isEqualTo(PredictionSource.MODEL);
		assertThat(prediction.needsReview()).isTrue();
		assertThat(prediction.confidence()).isLessThan(CategorizerEngine.ACCEPT);
		assertThat(prediction.item()).isEqualTo("exam");
		assertThat(prediction.explanation()).contains("looks most like Education & Stationery");
	}

	// ------------------------------------------------------------------ policy details

	@Test
	void confidenceBandsDecideReviewAndOther() {
		for (GoldSet.Row row : GoldSet.load()) {
			Prediction p = engine.categorize(null, row.text(), row.amount());
			if (p.category() == Category.OTHER) {
				assertThat(p.needsReview()).isTrue();
				assertThat(p.source()).isEqualTo(PredictionSource.ABSTAIN);
			}
			else {
				assertThat(p.confidence()).as(row.text()).isGreaterThanOrEqualTo(CategorizerEngine.REVIEW);
				assertThat(p.needsReview()).as(row.text()).isEqualTo(p.confidence() < CategorizerEngine.ACCEPT);
				assertThat(p.source()).isNotIn(PredictionSource.ABSTAIN, PredictionSource.USER_MEMORY);
			}
			assertThat(p.alternatives()).hasSizeLessThanOrEqualTo(3);
			for (int i = 1; i < p.alternatives().size(); i++) {
				assertThat(p.alternatives().get(i).confidence())
					.isLessThanOrEqualTo(p.alternatives().get(i - 1).confidence());
			}
			for (Prediction.Alternative alternative : p.alternatives()) {
				assertThat(alternative.category()).isNotEqualTo(Category.OTHER).isNotEqualTo(p.category());
			}
		}
	}

	@Test
	void anUnsureAnswerOffersItsBestGuessesAsAlternatives() {
		Prediction prediction = predict("bill");
		// "bill" alone could be rent, a phone bill, a restaurant bill ...
		if (prediction.category() == Category.OTHER) {
			assertThat(prediction.alternatives()).isNotEmpty();
			assertThat(prediction.explanation()).contains("could be");
		}
		assertThat(prediction.needsReview()).isTrue();
	}

	@Test
	void theAmountIsOnlyAWeakHint() {
		// a clear item keeps its category whatever the price
		for (String amount : List.of("5", "40", "400", "4000", "40000")) {
			Prediction prediction = engine.categorize(null, "dosa", new BigDecimal(amount));
			assertThat(prediction.category()).isEqualTo(Category.FOOD_MEALS);
			assertThat(prediction.confidence()).isGreaterThanOrEqualTo(0.80);
		}
		assertThat(engine.categorize(null, "dosa", BigDecimal.ZERO).category()).isEqualTo(Category.FOOD_MEALS);
		assertThat(engine.categorize(null, "dosa", new BigDecimal("-5")).category()).isEqualTo(Category.FOOD_MEALS);
	}

	@Test
	void worksBeforeTheModelIsTrained() {
		// right after start-up only the word lists and the spelling repair are there
		CategorizerEngine cold = CategorizerEngine.create(UserMemoryStore.NONE);
		assertThat(cold.isModelReady()).isFalse();
		assertThat(cold.categorize(null, "juse", null).category()).isEqualTo(Category.SNACKS_BEVERAGES);
		assertThat(cold.categorize(null, "bus ticket", null).confidence()).isGreaterThanOrEqualTo(0.80);
		Prediction unknown = cold.categorize(null, "blorfen", null);
		assertThat(unknown.category()).isEqualTo(Category.OTHER);
		assertThat(unknown.needsReview()).isTrue();
		assertThat(cold.awaitModel(java.time.Duration.ofMillis(1))).isFalse();

		cold.startTraining();
		cold.startTraining(); // a second call is harmless
		assertThat(cold.awaitModel(java.time.Duration.ofSeconds(30))).isTrue();
		assertThat(cold.isModelReady()).isTrue();
		assertThat(cold.stats().trainingExamples()).isGreaterThan(10_000);
		assertThat(cold.categorize(null, "juse", null).category()).isEqualTo(Category.SNACKS_BEVERAGES);
	}

	@ParameterizedTest
	@CsvSource({
			// a vague word
			"bill 500, RENT_UTILITIES", "fee, EDUCATION_STATIONERY", "charges, TRANSFERS_FEES",
			// a shop that sells many things
			"amazon, SHOPPING",
			// a repaired spelling, however close
			"juse, SNACKS_BEVERAGES", "briyany, FOOD_MEALS", "helmt fine, TRANSFERS_FEES" })
	void withoutTheModelAWeakOrRepairedMatchKeepsItsCategoryButAsksForReview(String text, Category category) {
		// before training has finished, after a failed training and with app.ai.training=off
		for (CategorizerEngine withoutModel : List.of(CategorizerEngine.create(UserMemoryStore.NONE),
				TestEngines.lexiconOnly())) {
			Prediction prediction = withoutModel.categorize(null, text, null);
			assertThat(prediction.category()).as(text).isEqualTo(category);
			assertThat(prediction.confidence()).as(text).isBetween(CategorizerEngine.REVIEW, 0.79);
			assertThat(prediction.needsReview()).as(text).isTrue();
		}
	}

	@ParameterizedTest
	@CsvSource({ "bus ticket, TRANSPORT_FUEL", "dosa, FOOD_MEALS", "dosa 2, FOOD_MEALS", "eb bill, RENT_UTILITIES",
			"jio recharge 239, MOBILE_INTERNET", "pen, EDUCATION_STATIONERY" })
	void withoutTheModelAnExactClearItemIsStillAcceptedSilently(String text, Category category) {
		Prediction prediction = CategorizerEngine.create(UserMemoryStore.NONE).categorize(null, text, null);
		assertThat(prediction.category()).as(text).isEqualTo(category);
		assertThat(prediction.confidence()).as(text).isGreaterThanOrEqualTo(CategorizerEngine.ACCEPT);
		assertThat(prediction.needsReview()).as(text).isFalse();
		assertThat(prediction.source()).as(text).isEqualTo(PredictionSource.LEXICON_EXACT);
	}

	@Test
	void onceTheModelAgreesARepairedSpellingIsAcceptedSilently() {
		Prediction prediction = predict("juse");
		assertThat(prediction.confidence()).isGreaterThanOrEqualTo(CategorizerEngine.ACCEPT);
		assertThat(prediction.needsReview()).isFalse();
	}

	// ------------------------------------------------------------------ robustness

	@ParameterizedTest
	@ValueSource(strings = { "\u0000\u0001\u0002", "🍕🍕🍕", "ஜூஸ் கடை", "जूस और चाय",
			"<script>alert(1)</script>", "'; DROP TABLE entries; --", "\ud83d", "á́́́",
			"‮‭ reversed", "dosa\tidli\nvada", "ⓙⓤⓘⓒⓔ", "ĵûíçé", "𝒿𝓊𝒾𝒸𝑒", "1234567890123456789012345678901234567890",
			"a b c d e f g h i j k l m n o p q r s t u v w x y z", "-----", "٣ دوسا" })
	void oddInputNeverBreaksIt(String text) {
		assertWellFormed(predict(text));
		assertWellFormed(engine.categorize(null, text, new BigDecimal("99999999.99")));
	}

	@Test
	void veryLongInputIsFineAndFast() {
		String huge = "biryani ".repeat(1250); // 10,000 characters
		assertThat(huge.length()).isEqualTo(10_000);
		long started = System.nanoTime();
		Prediction prediction = predict(huge);
		long millis = (System.nanoTime() - started) / 1_000_000;
		assertWellFormed(prediction);
		assertThat(prediction.category()).isEqualTo(Category.FOOD_MEALS);
		assertThat(prediction.item()).isEqualTo("biryani");
		assertThat(millis).isLessThan(200);

		assertWellFormed(predict("x".repeat(10_000)));
		assertWellFormed(predict("zq ".repeat(5_000)));
		assertWellFormed(predict("🍕".repeat(5_000)));
	}

	@Test
	void htmlNeverReachesItemOrExplanation() {
		Prediction prediction = predict("<b>dosa</b> <img src=x onerror=alert(1)>");
		assertThat(prediction.item()).doesNotContain("<", ">", "\"");
		assertThat(prediction.explanation()).doesNotContain("<", ">");
	}

	@Test
	void randomGarbageNeverThrows() {
		Random random = new Random(11);
		for (int round = 0; round < 3000; round++) {
			int length = random.nextInt(40);
			StringBuilder text = new StringBuilder();
			for (int i = 0; i < length; i++) {
				int kind = random.nextInt(6);
				if (kind == 0) {
					text.appendCodePoint(random.nextInt(0x10FFFF + 1) & ~0xD800); // anything, minus lone surrogates mostly
				}
				else if (kind == 1) {
					text.append((char) random.nextInt(0x3000)); // includes control characters and lone marks
				}
				else if (kind == 2) {
					text.append(' ');
				}
				else {
					text.append((char) ('a' + random.nextInt(26)));
				}
			}
			assertWellFormed(predict(text.toString()));
		}
	}

	@Test
	void isSafeToCallFromManyThreads() throws Exception {
		List<String> texts = List.of("juse", "briyany", "bus ticket", "pen", "recharge 199", "asdf", "amazon earphone",
				"kothu parotta", "eb bill", "xyzzy plugh");
		List<Prediction> expected = texts.stream().map(this::predict).toList();
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int thread = 0; thread < 8; thread++) {
				int offset = thread;
				Callable<Boolean> task = () -> {
					for (int i = 0; i < 2000; i++) {
						int index = (i + offset) % texts.size();
						// mix in never-seen words so the spelling cache is written concurrently too
						predict("zzq" + offset + "x" + i);
						if (!predict(texts.get(index)).equals(expected.get(index))) {
							return false;
						}
					}
					return true;
				};
				results.add(pool.submit(task));
			}
			for (Future<Boolean> result : results) {
				assertThat(result.get(60, TimeUnit.SECONDS)).isTrue();
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static void assertWellFormed(Prediction prediction) {
		assertThat(prediction).isNotNull();
		assertThat(prediction.category()).isNotNull();
		assertThat(prediction.confidence()).isBetween(0.0, 1.0);
		assertThat(prediction.item()).isNotBlank();
		assertThat(prediction.item().length()).isLessThanOrEqualTo(60);
		assertThat(prediction.item()).isEqualTo(prediction.item().toLowerCase(java.util.Locale.ROOT));
		assertThat(prediction.explanation()).isNotBlank().doesNotContain("<");
		assertThat(prediction.alternatives()).isNotNull().hasSizeLessThanOrEqualTo(3);
		assertThat(prediction.source()).isNotNull();
		if (prediction.category() == Category.OTHER) {
			assertThat(prediction.needsReview()).isTrue();
		}
	}

}
