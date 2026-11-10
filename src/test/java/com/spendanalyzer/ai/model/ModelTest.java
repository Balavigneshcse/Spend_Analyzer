package com.spendanalyzer.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.spendanalyzer.ai.lexicon.Knowledge;
import com.spendanalyzer.ai.text.TextAnalyzer;
import com.spendanalyzer.model.Category;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Features, trainer, calibration and the generated training data. */
class ModelTest {

	private static Knowledge knowledge;

	private static TextAnalyzer analyzer;

	private static final FeatureExtractor extractor = new FeatureExtractor();

	@BeforeAll
	static void load() {
		knowledge = Knowledge.load();
		analyzer = new TextAnalyzer(knowledge.stopWords(), knowledge.aliases(), knowledge.lexicon()::isWord);
	}

	private static SparseVector features(String text, String amount) {
		return extractor.extract(analyzer.analyze(text).tokens(), amount == null ? null : new BigDecimal(amount));
	}

	// ------------------------------------------------------------------ hashing and features

	@Test
	void murmurIsStableAndSpreadsWell() {
		assertThat(Murmur3.hash("dosa", 1)).isEqualTo(Murmur3.hash("dosa", 1));
		assertThat(Murmur3.hash("dosa", 1)).isNotEqualTo(Murmur3.hash("dosa", 2));
		assertThat(Murmur3.hash("dosa", 1)).isNotEqualTo(Murmur3.hash("dose", 1));
		assertThat(Murmur3.hash("xdosax", 1, 5, 9)).isEqualTo(Murmur3.hash("dosa", 9));
		// 20,000 different short strings into 65,536 slots: a good hash fills about a quarter of them
		java.util.BitSet used = new java.util.BitSet(FeatureExtractor.DIMENSION);
		for (int i = 0; i < 20_000; i++) {
			used.set(Murmur3.hash("w" + i, 7) & (FeatureExtractor.DIMENSION - 1));
		}
		assertThat(used.cardinality()).isBetween(16_000, 18_500);
	}

	@Test
	void featureFamiliesHaveFixedLength() {
		SparseVector one = features("dosa", null);
		SparseVector many = features("masala dosa with extra chutney sambar", null);
		assertThat(one.size()).isLessThan(many.size());
		// bias 1 + words 1 + letter groups 0.8^2 + sound 0.6^2 (a single word has no pairs)
		assertThat(squaredLength(one)).isCloseTo(1 + 1 + 0.64 + 0.36, within(1e-4));
		// the same with word pairs (0.7^2): a longer text is not "louder"
		assertThat(squaredLength(many)).isCloseTo(1 + 1 + 0.49 + 0.64 + 0.36, within(1e-4));
	}

	@Test
	void numbersLookAlikeAndFillersAreInvisible() {
		assertThat(features("recharge 199", null).index).containsExactly(features("recharge 239", null).index);
		assertThat(features("paid for dosa", null).index).containsExactly(features("dosa", null).index);
		assertThat(features("dosa", "40").size()).isEqualTo(features("dosa", null).size() + 1);
		assertThat(features("", null).size()).isEqualTo(1); // only the bias
	}

	@Test
	void amountBands() {
		assertThat(FeatureExtractor.amountBand(5)).isEqualTo(0);
		assertThat(FeatureExtractor.amountBand(10)).isEqualTo(0);
		assertThat(FeatureExtractor.amountBand(11)).isEqualTo(1);
		assertThat(FeatureExtractor.amountBand(199)).isEqualTo(4);
		assertThat(FeatureExtractor.amountBand(250_000)).isEqualTo(9);
	}

	private static double squaredLength(SparseVector vector) {
		double sum = 0;
		for (float v : vector.value) {
			sum += v * v;
		}
		return sum;
	}

	// ------------------------------------------------------------------ trainer

	@Test
	void learnsASmallProblemAndTheLossFalls() {
		String[][] data = { { "dosa", "0" }, { "idli", "0" }, { "biryani", "0" }, { "petrol", "1" }, { "bus", "1" },
				{ "auto", "1" }, { "pen", "2" }, { "pencil", "2" }, { "notebook", "2" } };
		List<SoftmaxTrainer.Example> examples = new ArrayList<>();
		for (String[] row : data) {
			examples.add(new SoftmaxTrainer.Example(features(row[0], null), Integer.parseInt(row[1])));
		}
		SoftmaxTrainer.Outcome outcome = SoftmaxTrainer.train(examples, 3, new SoftmaxTrainer.Options(20, 0.5, 1e-5, 1));
		double[] loss = outcome.lossPerEpoch();
		assertThat(loss[loss.length - 1]).isLessThan(loss[0] / 5);
		for (String[] row : data) {
			double[] p = outcome.model().probabilities(features(row[0], null));
			assertThat(p[Integer.parseInt(row[1])]).as(row[0]).isGreaterThan(0.8);
			assertThat(p[0] + p[1] + p[2]).isCloseTo(1.0, within(1e-9));
		}
		// letter patterns generalise: a spelling never seen still lands in the right class
		assertThat(outcome.model().probabilities(features("pencl", null))[2]).isGreaterThan(0.5);
	}

	@Test
	void softmaxIsSafeForHugeScores() {
		double[] p = SoftmaxModel.softmax(new double[] { 1000, 999, -1000 }, 1.0);
		assertThat(p[0] + p[1] + p[2]).isCloseTo(1.0, within(1e-12));
		assertThat(p[0]).isGreaterThan(p[1]);
		assertThat(p[2]).isLessThan(1e-12);
		double[] cooler = SoftmaxModel.softmax(new double[] { 2, 1, 0 }, 3.0);
		assertThat(cooler[0]).isLessThan(SoftmaxModel.softmax(new double[] { 2, 1, 0 }, 1.0)[0]);
	}

	@Test
	void temperatureCalibrationCoolsAnOverconfidentModel() {
		// train on clean words, then hold out examples where a third of the labels disagree
		List<SoftmaxTrainer.Example> train = new ArrayList<>();
		List<SoftmaxTrainer.Example> heldOut = new ArrayList<>();
		String[] food = { "dosa", "idli", "pongal", "poori", "meals", "biryani" };
		String[] travel = { "bus", "auto", "petrol", "train", "metro", "cab" };
		for (int i = 0; i < food.length; i++) {
			train.add(new SoftmaxTrainer.Example(features(food[i], null), 0));
			train.add(new SoftmaxTrainer.Example(features(travel[i], null), 1));
			heldOut.add(new SoftmaxTrainer.Example(features(food[i], null), i % 3 == 0 ? 1 : 0));
			heldOut.add(new SoftmaxTrainer.Example(features(travel[i], null), i % 3 == 0 ? 0 : 1));
		}
		SoftmaxModel model = SoftmaxTrainer.train(train, 2, new SoftmaxTrainer.Options(30, 0.5, 1e-5, 1)).model();
		double temperature = TemperatureCalibrator.fit(model, heldOut);
		assertThat(temperature).isGreaterThan(1.0);
		assertThat(TemperatureCalibrator.fit(model, List.of())).isEqualTo(1.0);
		// calibration changes confidence, never the winner
		double[] hot = model.probabilities(features("dosa", null));
		double[] cool = model.withTemperature(temperature).probabilities(features("dosa", null));
		assertThat(cool[0]).isLessThan(hot[0]).isGreaterThan(0.5);
	}

	// ------------------------------------------------------------------ generated training data

	@Test
	void typoGeneratorIsRepeatableAndKeepsTheFirstLetter() {
		Random first = new Random(3);
		Random second = new Random(3);
		for (String word : List.of("biryani", "recharge", "notebook", "petrol", "chocolate", "medicine")) {
			String typo = TypoAugmenter.misspell(word, first);
			assertThat(typo).isEqualTo(TypoAugmenter.misspell(word, second));
			assertThat(typo).isNotEqualTo(word);
			assertThat(typo.charAt(0)).isEqualTo(word.charAt(0));
			assertThat(Math.abs(typo.length() - word.length())).isLessThanOrEqualTo(1);
		}
		assertThat(TypoAugmenter.misspell("tea", new Random(1))).isEqualTo("tea"); // too short to damage safely
	}

	@Test
	void seedCorpusIsRepeatableBalancedEnoughAndNeverTeachesOther() {
		List<SeedCorpusGenerator.SeedExample> corpus = SeedCorpusGenerator.generate(knowledge, 42);
		assertThat(corpus).isEqualTo(SeedCorpusGenerator.generate(knowledge, 42));
		assertThat(corpus.size()).isBetween(10_000, 40_000);
		int[] perClass = new int[Classes.count()];
		int withAmount = 0;
		for (SeedCorpusGenerator.SeedExample example : corpus) {
			assertThat(example.category()).isNotEqualTo(Category.OTHER);
			perClass[Classes.indexOf(example.category())]++;
			if (example.amount() != null) {
				withAmount++;
				assertThat(example.amount().signum()).isPositive();
			}
		}
		for (int c = 0; c < perClass.length; c++) {
			assertThat(perClass[c]).as(Classes.category(c).name()).isGreaterThan(500);
		}
		assertThat(withAmount / (double) corpus.size()).isBetween(0.4, 0.6);
	}

	@Test
	void classesAreAllCategoriesExceptOther() {
		assertThat(Classes.count()).isEqualTo(Category.values().length - 1);
		assertThat(Classes.indexOf(Category.OTHER)).isEqualTo(-1);
		for (int c = 0; c < Classes.count(); c++) {
			assertThat(Classes.indexOf(Classes.category(c))).isEqualTo(c);
		}
	}

	// ------------------------------------------------------------------ the real bootstrap

	@Test
	void bootstrapIsDeterministicSmallAndFastEnough() {
		ModelBootstrap.Trained first = ModelBootstrap.train(knowledge, analyzer, extractor);
		ModelBootstrap.Trained second = ModelBootstrap.train(knowledge, analyzer, extractor);
		System.out.printf("model bootstrap: %d examples, %d ms then %d ms, temperature %.1f, %d KB, loss %s%n",
				first.examples(), first.millis(), second.millis(), first.model().temperature(),
				first.model().sizeInBytes() / 1024, java.util.Arrays.toString(first.lossPerEpoch()));
		// same seed, same data: bit-for-bit the same weights
		assertThat(second.model().checksum()).isEqualTo(first.model().checksum());
		assertThat(second.model().temperature()).isEqualTo(first.model().temperature());
		// the loss falls from pass to pass
		double[] loss = first.lossPerEpoch();
		for (int epoch = 1; epoch < loss.length; epoch++) {
			assertThat(loss[epoch]).isLessThan(loss[epoch - 1]);
		}
		assertThat(loss[loss.length - 1]).isLessThan(0.1);
		// budget: weights at most 8 MB; training about 1.5 s on a laptop (a generous bound for slow CI)
		assertThat(first.model().sizeInBytes()).isLessThanOrEqualTo(8L * 1024 * 1024);
		assertThat(first.model().classes()).isEqualTo(Classes.count());
		assertThat(second.millis()).isLessThan(5_000);
		assertThat(first.model().temperature()).isBetween(0.5, 4.0);
	}

	@Test
	void gramMemoryTellsWordsFromNoise() {
		GramMemory memory = new GramMemory(knowledge.lexicon().words());
		assertThat(memory.familiarity("biryani")).isEqualTo(1.0);
		assertThat(memory.familiarity("biryanis")).isGreaterThan(0.6);
		assertThat(memory.familiarity("asdf")).isLessThan(0.3);
		assertThat(memory.familiarity("qwerty")).isLessThan(0.5);
		assertThat(memory.familiarity("lkjhg")).isEqualTo(0.0);
		assertThat(memory.familiarity("zz")).isEqualTo(0.0);
		assertThat(memory.familiarity("")).isEqualTo(0.0);
	}

}
