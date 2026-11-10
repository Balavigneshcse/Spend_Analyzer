package com.spendanalyzer.ai.model;

import java.util.ArrayList;
import java.util.List;

import com.spendanalyzer.ai.lexicon.Knowledge;
import com.spendanalyzer.ai.text.TextAnalyzer;

/**
 * Trains the model from scratch: generate the seed examples from the lexicon, turn them into
 * features with the very same text pipeline that live predictions use, run the trainer, then fit
 * the temperature on the hand-labelled calibration phrases. Takes well under a second on a laptop.
 */
public final class ModelBootstrap {

	/** Seed for the generated examples and the shuffling; fixed so every start gives the same model. */
	public static final long SEED = 42L;

	/**
	 * @param model the calibrated model
	 * @param examples how many generated examples it was trained on
	 * @param lossPerEpoch training loss of each pass
	 * @param millis wall-clock time of the whole bootstrap
	 */
	public record Trained(SoftmaxModel model, int examples, double[] lossPerEpoch, long millis) {
	}

	private ModelBootstrap() {
	}

	public static Trained train(Knowledge knowledge, TextAnalyzer analyzer, FeatureExtractor extractor) {
		return train(knowledge, analyzer, extractor, SoftmaxTrainer.Options.DEFAULT);
	}

	public static Trained train(Knowledge knowledge, TextAnalyzer analyzer, FeatureExtractor extractor,
			SoftmaxTrainer.Options options) {
		long started = System.nanoTime();
		List<SeedCorpusGenerator.SeedExample> seed = SeedCorpusGenerator.generate(knowledge, SEED);
		List<SoftmaxTrainer.Example> examples = new ArrayList<>(seed.size());
		for (SeedCorpusGenerator.SeedExample example : seed) {
			examples.add(new SoftmaxTrainer.Example(
					extractor.extract(analyzer.analyze(example.text()).tokens(), example.amount()),
					Classes.indexOf(example.category())));
		}
		SoftmaxTrainer.Outcome outcome = SoftmaxTrainer.train(examples, Classes.count(), options);

		List<SoftmaxTrainer.Example> heldOut = new ArrayList<>(knowledge.calibration().size());
		for (Knowledge.LabelledPhrase phrase : knowledge.calibration()) {
			int label = Classes.indexOf(phrase.category());
			if (label >= 0) {
				heldOut.add(new SoftmaxTrainer.Example(
						extractor.extract(analyzer.analyze(phrase.text()).tokens(), phrase.amount()), label));
			}
		}
		double temperature = TemperatureCalibrator.fit(outcome.model(), heldOut);
		long millis = (System.nanoTime() - started) / 1_000_000;
		return new Trained(outcome.model().withTemperature(temperature), examples.size(), outcome.lossPerEpoch(),
				millis);
	}

}
