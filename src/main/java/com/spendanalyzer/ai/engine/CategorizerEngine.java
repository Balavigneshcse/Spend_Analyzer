package com.spendanalyzer.ai.engine;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.spendanalyzer.ai.api.Prediction;
import com.spendanalyzer.ai.api.PredictionSource;
import com.spendanalyzer.ai.fuzzy.FuzzyMatcher;
import com.spendanalyzer.ai.learn.UserMemoryStore;
import com.spendanalyzer.ai.lexicon.Knowledge;
import com.spendanalyzer.ai.lexicon.LexiconEntry;
import com.spendanalyzer.ai.lexicon.LexiconMatcher;
import com.spendanalyzer.ai.lexicon.LexiconMatcher.Span;
import com.spendanalyzer.ai.model.Classes;
import com.spendanalyzer.ai.model.FeatureExtractor;
import com.spendanalyzer.ai.model.GramMemory;
import com.spendanalyzer.ai.model.ModelBootstrap;
import com.spendanalyzer.ai.text.AnalyzedText;
import com.spendanalyzer.ai.text.TextAnalyzer;
import com.spendanalyzer.model.Category;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The categoriser itself: plain Java, no Spring, no database. It asks, in this order:
 * <ol>
 * <li>the user's own memory ("you called this text Snacks before")</li>
 * <li>the hand-written lexicon, with spelling and sound repair ("juse" sounds like "juice")</li>
 * <li>the self-trained softmax model, which reads letter and sound patterns</li>
 * </ol>
 * and blends lexicon and model into one probability per category. How much the lexicon counts
 * depends on how good its match is: an exact, clear item nearly decides alone, a shaky spelling
 * match needs the model to agree, and without any known word the model decides (if the word looks
 * like real words at all). Below the confidence bands the answer is OTHER, "please check".
 *
 * <p>Thread-safe. Everything is immutable except the trained model, which is published once
 * through an atomic reference; until then the lexicon answers alone.
 */
public final class CategorizerEngine {

	private static final Logger log = LoggerFactory.getLogger(CategorizerEngine.class);

	/** Which layers take part. Only tests switch layers off, to measure what each one adds. */
	public record Options(boolean useLexicon, boolean useModel) {

		public static final Options FULL = new Options(true, true);

		public static final Options LEXICON_ONLY = new Options(true, false);

		public static final Options MODEL_ONLY = new Options(false, true);

	}

	/** Every category with its probability, best first, before the confidence rules are applied. */
	public record Ranking(List<Prediction.Alternative> ranked) {

		public Category top() {
			return ranked.isEmpty() ? Category.OTHER : ranked.get(0).category();
		}

	}

	/** Sizes and timings, for the logs and the tests. */
	public record Stats(int items, int terms, int vocabulary, long loadMillis, boolean modelReady, int trainingExamples,
			long trainMillis, double temperature, long modelBytes) {
	}

	/** At or above this the category is accepted silently. */
	public static final double ACCEPT = 0.80;

	/** Between this and {@link #ACCEPT} the category is used but flagged for review. */
	public static final double REVIEW = 0.55;

	/** The winner must lead the runner-up by this much, else the answer is OTHER. */
	public static final double MARGIN = 0.15;

	public static final double MEMORY_CONFIDENCE = 0.99;

	/** The highest confidence an OTHER answer reports. */
	public static final double ABSTAIN_CAP = 0.54;

	/** Share of the decision a perfect lexicon match gets; the model keeps the rest. */
	static final double LEXICON_SHARE = 0.8;

	/** Probability mass kept for categories without any lexicon evidence. */
	static final double SMOOTHING = 0.004;

	/** A second spelling match this close to the best one makes the match ambiguous. */
	static final double RIVAL_GAP = 0.06;

	/** A word with a smaller share of familiar letter groups than this is noise, not a misspelling. */
	static final double MIN_FAMILIARITY = 0.6;

	/**
	 * Without any known word the model is guessing from letter patterns. Such a guess may pick the
	 * category, but it stays just under {@link #ACCEPT} so the user is always asked to confirm it.
	 */
	static final double MODEL_ONLY_CAP = 0.79;

	static final double CORRECTION_CREDIT = 1.0;

	static final double CONFIRMATION_CREDIT = 0.25;

	static final double MAX_WORD_WEIGHT = 3.0;

	/** From this weight on, what the user taught about a word replaces the general lexicon. */
	static final double OVERRIDE_WEIGHT = 0.75;

	/** A word the user taught counts twice as much as a clear item of the general word list. */
	static final double TAUGHT_WORD_EVIDENCE = 2.0;

	/** Weaker user evidence (a word from a longer corrected text) is counted at a discount. */
	static final double PARTIAL_CREDIT_DISCOUNT = 0.6;

	/** The trained model, shared by an engine and its test variants. */
	private static final class ModelHolder {

		volatile ModelBootstrap.Trained trained;

		final CompletableFuture<ModelBootstrap.Trained> ready = new CompletableFuture<>();

		final AtomicBoolean started = new AtomicBoolean();

	}

	/** Builds its value on first use, once, whichever thread comes first. */
	private static final class Lazy<T> {

		private final Supplier<T> factory;

		private volatile T value;

		Lazy(Supplier<T> factory) {
			this.factory = factory;
		}

		T get() {
			T built = value;
			if (built == null) {
				synchronized (this) {
					built = value;
					if (built == null) {
						built = factory.get();
						value = built;
					}
				}
			}
			return built;
		}

	}

	/** What the user's memory says about one text. */
	private record Taught(Category remembered, Map<String, Map<Category, Double>> wordWeights) {

		static final Taught NOTHING = new Taught(null, Map.of());

	}

	/** The blended probabilities and where they came from. */
	private record Scored(double[] probability, double[] evidence, boolean modelOnly, Span[] bestSpan,
			String taughtWord, int taughtClass, double familiarity) {
	}

	private final Knowledge knowledge;

	private final TextAnalyzer analyzer;

	private final Lazy<LexiconMatcher> matcher;

	private final FeatureExtractor extractor;

	private final Lazy<GramMemory> grams;

	private final UserMemoryStore store;

	private final Options options;

	private final ModelHolder holder;

	private final long loadMillis;

	private final int vocabulary;

	/** Loads the knowledge files from the classpath. The model is not trained yet. */
	public static CategorizerEngine create(UserMemoryStore store) {
		long started = System.nanoTime();
		Knowledge knowledge = Knowledge.load();
		return new CategorizerEngine(knowledge, store, started);
	}

	public CategorizerEngine(Knowledge knowledge, UserMemoryStore store) {
		this(knowledge, store, System.nanoTime());
	}

	private CategorizerEngine(Knowledge knowledge, UserMemoryStore store, long startedNanos) {
		this.knowledge = knowledge;
		this.store = store == null ? UserMemoryStore.NONE : store;
		this.options = Options.FULL;
		this.holder = new ModelHolder();
		this.analyzer = new TextAnalyzer(knowledge.stopWords(), knowledge.aliases(), knowledge.lexicon()::isWord);
		// two-letter words ("eb", "vi") are matched exactly or not at all
		Set<String> targets = new LinkedHashSet<>();
		for (String word : knowledge.lexicon().words()) {
			if (word.length() >= 3) {
				targets.add(word);
			}
		}
		this.vocabulary = targets.size();
		// The spelling index and the letter-group table cost about a tenth of a second on a cold
		// JVM. They are built on first use (the background trainer sees to that), not at start-up.
		this.matcher = new Lazy<>(() -> new LexiconMatcher(knowledge.lexicon(),
				new FuzzyMatcher(targets, knowledge.lexicon().writtenWords())));
		this.extractor = new FeatureExtractor();
		this.grams = new Lazy<>(() -> new GramMemory(knowledge.lexicon().words()));
		this.loadMillis = (System.nanoTime() - startedNanos) / 1_000_000;
	}

	private CategorizerEngine(CategorizerEngine base, UserMemoryStore store, Options options) {
		this.knowledge = base.knowledge;
		this.analyzer = base.analyzer;
		this.matcher = base.matcher;
		this.extractor = base.extractor;
		this.grams = base.grams;
		this.holder = base.holder;
		this.loadMillis = base.loadMillis;
		this.vocabulary = base.vocabulary;
		this.store = store == null ? UserMemoryStore.NONE : store;
		this.options = options;
	}

	/** The same knowledge and trained model with some layers switched off or another memory. */
	public CategorizerEngine variant(Options variantOptions, UserMemoryStore variantStore) {
		return new CategorizerEngine(this, variantStore, variantOptions);
	}

	// ------------------------------------------------------------------ training

	/** Trains the model on the calling thread and publishes it. Safe to call more than once. */
	public ModelBootstrap.Trained trainNow() {
		holder.started.set(true);
		ModelBootstrap.Trained trained = ModelBootstrap.train(knowledge, analyzer, extractor);
		holder.trained = trained;
		holder.ready.complete(trained);
		return trained;
	}

	/**
	 * Trains the model on a low-priority background thread; returns at once. Until training is
	 * done the lexicon answers alone. Calling it again does nothing.
	 */
	public void startTraining() {
		if (!holder.started.compareAndSet(false, true)) {
			return;
		}
		Thread trainer = new Thread(() -> {
			try {
				// first the spelling index, so the very first request does not have to build it
				matcher.get();
				grams.get();
				ModelBootstrap.Trained trained = trainNow();
				log.info("AI model trained: {} examples, {} ms, temperature {}, {} KB", trained.examples(),
						trained.millis(), trained.model().temperature(), trained.model().sizeInBytes() / 1024);
			}
			catch (Throwable ex) {
				// the lexicon keeps working without the model; never take the application down
				log.error("AI model training failed; continuing with the word list only", ex);
				holder.ready.completeExceptionally(ex);
			}
		}, "ai-model-trainer");
		trainer.setDaemon(true);
		trainer.setPriority(Thread.MIN_PRIORITY);
		trainer.start();
	}

	public boolean isModelReady() {
		return holder.trained != null;
	}

	/** Waits until the model is trained. @return false when the time ran out or training failed */
	public boolean awaitModel(Duration timeout) {
		try {
			holder.ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			return true;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
		catch (ExecutionException | TimeoutException ex) {
			return false;
		}
	}

	public Stats stats() {
		ModelBootstrap.Trained trained = holder.trained;
		return new Stats(knowledge.lexicon().canonicalCount(), knowledge.lexicon().termCount(), vocabulary, loadMillis,
				trained != null, trained == null ? 0 : trained.examples(), trained == null ? 0 : trained.millis(),
				trained == null ? 1.0 : trained.model().temperature(),
				trained == null ? 0 : trained.model().sizeInBytes());
	}

	public Knowledge knowledge() {
		return knowledge;
	}

	// ------------------------------------------------------------------ predicting

	/**
	 * Never throws for any text. Problems of the memory store (a database that is down) are not
	 * swallowed: hiding them would leave the caller's transaction in an unknown state.
	 * @param userId whose memory to use; null for general knowledge only
	 * @param amount weak hint, may be null
	 */
	public Prediction categorize(UUID userId, String text, BigDecimal amount) {
		AnalyzedText analyzed;
		try {
			analyzed = analyzer.analyze(text);
		}
		catch (RuntimeException ex) {
			log.warn("AI could not read a text", ex);
			return abstain(AnalyzedText.UNKNOWN_ITEM, 0.0, Explanations.nothingToRead(), List.of());
		}
		Taught taught = Taught.NOTHING;
		if (userId != null && !analyzed.tokens().isEmpty()) {
			Optional<Category> remembered = store.recall(userId, analyzed.memoryKey());
			taught = remembered.isPresent() ? new Taught(remembered.get(), Map.of())
					: new Taught(null, store.wordWeights(userId, analyzed.contentWords()));
		}
		try {
			return decide(analyzed, taught, amount);
		}
		catch (RuntimeException ex) {
			log.warn("AI failed on a text; answering 'not sure'", ex);
			return abstain(analyzed.cleanedItem(), 0.0, Explanations.unknown(analyzed.cleanedItem()), List.of());
		}
	}

	/** The raw ranking of all categories for a text, without memory and confidence rules. */
	public Ranking rank(String text, BigDecimal amount) {
		AnalyzedText analyzed = analyzer.analyze(text);
		if (!analyzed.hasContent()) {
			return new Ranking(List.of());
		}
		LexiconMatcher.Result lexical = options.useLexicon() ? matcher.get().match(analyzed.tokens())
				: LexiconMatcher.Result.EMPTY;
		Scored scored = score(analyzed, lexical, Taught.NOTHING, amount);
		if (scored == null) {
			return new Ranking(List.of());
		}
		List<Prediction.Alternative> ranked = new ArrayList<>();
		for (int index : order(scored.probability())) {
			ranked.add(new Prediction.Alternative(Classes.category(index), scored.probability()[index]));
		}
		return new Ranking(ranked);
	}

	private Prediction decide(AnalyzedText analyzed, Taught taught, BigDecimal amount) {
		LexiconMatcher.Result lexical = options.useLexicon() ? matcher.get().match(analyzed.tokens())
				: LexiconMatcher.Result.EMPTY;
		String cleaned = analyzed.cleanedItem();
		if (taught.remembered() != null) {
			Category category = taught.remembered();
			return new Prediction(category, MEMORY_CONFIDENCE, item(lexical.spans(), category, cleaned),
					Explanations.remembered(cleaned, category), false, List.of(), PredictionSource.USER_MEMORY);
		}
		if (!analyzed.hasContent()) {
			return abstain(cleaned, 0.0, Explanations.nothingToRead(), List.of());
		}
		Scored scored = score(analyzed, lexical, taught, amount);
		if (scored == null) {
			return abstain(cleaned, 0.0, Explanations.unknown(cleaned), List.of());
		}
		double[] p = scored.probability();
		int[] order = order(p);
		int top = order[0];
		double confidence = scored.modelOnly() ? Math.min(p[top], MODEL_ONLY_CAP) : p[top];
		confidence = round(Math.max(0.0, Math.min(1.0, confidence)));
		if (scored.modelOnly() && scored.familiarity() < MIN_FAMILIARITY) {
			// letters that occur in no known word: the model's guess would be made up
			return abstain(cleaned, confidence, Explanations.unknown(cleaned), List.of());
		}
		double margin = p[top] - p[order[1]];
		if (confidence < REVIEW || margin < MARGIN) {
			List<Prediction.Alternative> candidates = alternatives(p, order, 0);
			List<Category> names = candidates.stream().map(Prediction.Alternative::category).toList();
			return abstain(cleaned, confidence, Explanations.unsure(cleaned, names), candidates);
		}

		Category category = Classes.category(top);
		PredictionSource source;
		String explanation;
		Span span = scored.bestSpan()[top];
		if (scored.modelOnly()) {
			source = PredictionSource.MODEL;
			explanation = Explanations.model(cleaned, category);
		}
		else if (scored.taughtClass() == top) {
			source = PredictionSource.ENSEMBLE;
			explanation = Explanations.taughtWord(scored.taughtWord(), category);
		}
		else if (span != null && top == argmax(scored.evidence()) && conflict(scored.evidence()) < 0.5) {
			// the word list was clear on its own; when its words pulled apart, the model settled it
			source = span.exact() ? PredictionSource.LEXICON_EXACT : PredictionSource.LEXICON_FUZZY;
			explanation = Explanations.lexicon(span, canonical(span, category), category);
		}
		else {
			source = PredictionSource.ENSEMBLE;
			explanation = Explanations.combined(category);
		}
		return new Prediction(category, confidence, item(lexical.spans(), category, cleaned), explanation,
				confidence < ACCEPT, alternatives(p, order, 1), source);
	}

	/**
	 * Blends lexicon evidence, what the user taught about single words, and the model.
	 * @return null when no layer has anything to say
	 */
	private Scored score(AnalyzedText analyzed, LexiconMatcher.Result lexical, Taught taught, BigDecimal amount) {
		int classes = Classes.count();
		double[] evidence = new double[classes];
		Span[] bestSpan = new Span[classes];
		double[] bestSpanValue = new double[classes];
		double strength = 0;

		// what this user taught about single words
		Map<String, Integer> override = new HashMap<>();
		String taughtWord = null;
		int taughtClass = -1;
		double taughtWeight = 0;
		for (Map.Entry<String, Map<Category, Double>> word : taught.wordWeights().entrySet()) {
			int best = -1;
			double bestWeight = 0;
			for (Map.Entry<Category, Double> weight : word.getValue().entrySet()) {
				int index = Classes.indexOf(weight.getKey());
				if (index >= 0 && weight.getValue() > bestWeight) {
					best = index;
					bestWeight = weight.getValue();
				}
			}
			if (best < 0) {
				continue;
			}
			if (bestWeight >= OVERRIDE_WEIGHT) {
				override.put(word.getKey(), best);
				evidence[best] += TAUGHT_WORD_EVIDENCE;
				strength = 1.0;
				if (bestWeight > taughtWeight) {
					taughtWord = word.getKey();
					taughtClass = best;
					taughtWeight = bestWeight;
				}
			}
			else {
				for (Map.Entry<Category, Double> weight : word.getValue().entrySet()) {
					int index = Classes.indexOf(weight.getKey());
					if (index >= 0) {
						evidence[index] += PARTIAL_CREDIT_DISCOUNT * weight.getValue();
						strength = Math.max(strength, PARTIAL_CREDIT_DISCOUNT * weight.getValue());
					}
				}
			}
		}

		// what the lexicon found
		for (Span span : lexical.spans()) {
			if (contradictsUser(span, override)) {
				continue; // the user said this word means something else for them
			}
			int tokens = span.top().tokenCount();
			double base = span.similarity() * span.similarity() * (1.0 + 0.25 * (tokens - 1));
			boolean hasRival = !span.rivalEntries().isEmpty()
					&& span.rivalSimilarity() >= span.similarity() - RIVAL_GAP;
			for (LexiconEntry entry : span.entries()) {
				int index = Classes.indexOf(entry.category());
				double value = entry.weight() * base;
				evidence[index] += value;
				if (value > bestSpanValue[index]) {
					bestSpanValue[index] = value;
					bestSpan[index] = span;
				}
			}
			double spanStrength = span.top().weight() * base;
			if (hasRival) {
				for (LexiconEntry rival : span.rivalEntries()) {
					evidence[Classes.indexOf(rival.category())] += rival.weight() * span.rivalSimilarity()
							* span.rivalSimilarity();
				}
			}
			strength = Math.max(strength, spanStrength);
		}

		double total = 0;
		for (double value : evidence) {
			total += value;
		}
		double[] fromLexicon = null;
		if (total > 0) {
			fromLexicon = new double[classes];
			for (int c = 0; c < classes; c++) {
				fromLexicon[c] = (evidence[c] + SMOOTHING) / (total + SMOOTHING * classes);
			}
		}
		double[] fromModel = null;
		ModelBootstrap.Trained trained = holder.trained;
		if (options.useModel() && trained != null) {
			fromModel = trained.model().probabilities(extractor.extract(analyzed.tokens(), amount));
		}

		// The model was trained on general knowledge and cannot know what this user taught, so it
		// stays out when a taught word is in the text.
		if (fromLexicon != null && fromModel != null && override.isEmpty()) {
			double lexiconShare = LEXICON_SHARE * Math.min(1.0, strength);
			// When the known words disagree among themselves ("chicken" says food, "rice" says
			// groceries) the lexicon still narrows the choice, and the model settles it at full voice.
			double modelShare = 1.0 - lexiconShare * (1.0 - conflict(evidence));
			return new Scored(pool(fromModel, modelShare, fromLexicon, lexiconShare), evidence, false, bestSpan,
					taughtWord, taughtClass, 1.0);
		}
		if (fromLexicon != null) {
			return new Scored(fromLexicon, evidence, false, bestSpan, taughtWord, taughtClass, 1.0);
		}
		if (fromModel != null) {
			return new Scored(fromModel, evidence, true, bestSpan, null, -1, familiarity(analyzed));
		}
		return null;
	}

	/** 0 when all evidence points one way, 1 when two categories have equally much of it. */
	private static double conflict(double[] evidence) {
		double top = 0;
		double second = 0;
		for (double value : evidence) {
			if (value > top) {
				second = top;
				top = value;
			}
			else if (value > second) {
				second = value;
			}
		}
		return top > 0 ? second / top : 0.0;
	}

	/** True when the user taught one of the span's words a category the span does not have. */
	private static boolean contradictsUser(Span span, Map<String, Integer> override) {
		if (override.isEmpty()) {
			return false;
		}
		for (String word : span.sourceWords()) {
			Integer taughtClass = override.get(word);
			if (taughtClass != null) {
				boolean agrees = false;
				for (LexiconEntry entry : span.entries()) {
					agrees |= Classes.indexOf(entry.category()) == taughtClass;
				}
				if (!agrees) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Weighted geometric mean of two probability vectors ("logarithmic opinion pool"): a category
	 * needs support from both sides to score high, in proportion to each side's share.
	 */
	private static double[] pool(double[] model, double modelShare, double[] lexicon, double lexiconShare) {
		double[] pooled = new double[model.length];
		double sum = 0;
		for (int c = 0; c < model.length; c++) {
			pooled[c] = Math.exp(modelShare * Math.log(Math.max(model[c], 1e-9))
					+ lexiconShare * Math.log(Math.max(lexicon[c], 1e-9)));
			sum += pooled[c];
		}
		for (int c = 0; c < pooled.length; c++) {
			pooled[c] /= sum;
		}
		return pooled;
	}

	/** Average share of familiar letter groups over the words of the text. */
	private double familiarity(AnalyzedText analyzed) {
		List<String> words = analyzed.contentWords();
		double sum = 0;
		for (String word : words) {
			// one or two letters ("aa", "zz") are never enough to guess from
			sum += word.length() < 3 ? 0.0 : grams.get().familiarity(word);
		}
		return words.isEmpty() ? 0.0 : sum / words.size();
	}

	/**
	 * The canonical item: the strongest known terms that belong to the chosen category, in text
	 * order ("jio recharge"); the cleaned text when no known term supports the category.
	 */
	private static String item(List<Span> spans, Category category, String cleaned) {
		double strongest = 0;
		for (Span span : spans) {
			for (LexiconEntry entry : span.entries()) {
				if (entry.category() == category) {
					strongest = Math.max(strongest, entry.weight());
				}
			}
		}
		if (strongest == 0) {
			return cleaned;
		}
		StringBuilder item = new StringBuilder();
		Set<String> seen = new LinkedHashSet<>();
		for (Span span : spans) {
			for (LexiconEntry entry : span.entries()) {
				if (entry.category() == category && entry.weight() == strongest && seen.add(entry.canonical())) {
					boolean fits = item.length() + entry.canonical().length() + 1 <= AnalyzedText.MAX_ITEM_CHARS;
					if (fits && seen.size() <= 3) {
						if (!item.isEmpty()) {
							item.append(' ');
						}
						item.append(entry.canonical());
					}
				}
			}
		}
		return item.isEmpty() ? cleaned : item.toString();
	}

	private static String canonical(Span span, Category category) {
		for (LexiconEntry entry : span.entries()) {
			if (entry.category() == category) {
				return entry.canonical();
			}
		}
		return span.top().canonical();
	}

	private static Prediction abstain(String item, double confidence, String explanation,
			List<Prediction.Alternative> alternatives) {
		// "not sure" never carries a confidence that reads like an accepted answer
		return new Prediction(Category.OTHER, Math.min(confidence, ABSTAIN_CAP), item, explanation, true, alternatives,
				PredictionSource.ABSTAIN);
	}

	/** Up to three categories from the ranking, starting at {@code from}, that are worth showing. */
	private static List<Prediction.Alternative> alternatives(double[] p, int[] order, int from) {
		List<Prediction.Alternative> list = new ArrayList<>(3);
		for (int i = from; i < order.length && list.size() < 3; i++) {
			if (p[order[i]] < 0.01) {
				break;
			}
			list.add(new Prediction.Alternative(Classes.category(order[i]), round(p[order[i]])));
		}
		return List.copyOf(list);
	}

	/** Class indexes sorted by probability, highest first (ties keep enum order). */
	private static int[] order(double[] p) {
		int[] order = new int[p.length];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		for (int i = 1; i < order.length; i++) {
			int current = order[i];
			int j = i - 1;
			while (j >= 0 && p[order[j]] < p[current]) {
				order[j + 1] = order[j];
				j--;
			}
			order[j + 1] = current;
		}
		return order;
	}

	private static int argmax(double[] values) {
		int best = 0;
		for (int i = 1; i < values.length; i++) {
			if (values[i] > values[best]) {
				best = i;
			}
		}
		return best;
	}

	private static double round(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}

	// ------------------------------------------------------------------ learning

	/**
	 * Remembers the user's choice for this text and credits its words, so similar texts follow.
	 * A choice of OTHER that the user merely left untouched teaches nothing.
	 */
	public void learn(UUID userId, String text, Category category, boolean wasCorrection) {
		if (userId == null || category == null) {
			return;
		}
		if (category == Category.OTHER && !wasCorrection) {
			return;
		}
		AnalyzedText analyzed;
		try {
			analyzed = analyzer.analyze(text);
		}
		catch (RuntimeException ex) {
			log.warn("AI could not read a text to learn from", ex);
			return;
		}
		if (analyzed.tokens().isEmpty()) {
			return;
		}
		store.remember(userId, analyzed.memoryKey(), category);
		Set<String> words = new LinkedHashSet<>(analyzed.contentWords());
		if (category == Category.OTHER || words.isEmpty()) {
			return;
		}
		// the credit is shared: one corrected word is certain, a word out of three much less so
		double credit = (wasCorrection ? CORRECTION_CREDIT : CONFIRMATION_CREDIT) / words.size();
		store.reinforce(userId, words, category, credit, MAX_WORD_WEIGHT, wasCorrection);
	}

}
