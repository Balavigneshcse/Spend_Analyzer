package com.spendanalyzer.ai.model;

import java.math.BigDecimal;
import java.util.List;

import com.spendanalyzer.ai.fuzzy.PhoneticKey;
import com.spendanalyzer.ai.text.Token;

/**
 * Describes a text to the model as numbers. Five families of features are hashed into one table of
 * 65,536 slots ("feature hashing": no dictionary to store, unseen spellings still land somewhere):
 * <ul>
 * <li>whole words ("dosa")</li>
 * <li>neighbouring word pairs ("bus ticket", "recharge" followed by a number)</li>
 * <li>groups of 2, 3 and 4 letters with word boundaries ("^do", "osa$"): these survive typos</li>
 * <li>the sound key of each word (JUS for "juse" and "juice")</li>
 * <li>a rough price band, as a weak tie-breaker</li>
 * </ul>
 * Each family is scaled to a fixed length so none can drown the others. Stateless and thread-safe.
 */
public final class FeatureExtractor {

	public static final int BITS = 16;

	public static final int DIMENSION = 1 << BITS;

	/** How loud each family is. */
	public record Weights(float words, float pairs, float letters, float sound, float amount) {

		public static final Weights DEFAULT = new Weights(1.0f, 0.7f, 0.8f, 0.6f, 0.25f);

	}

	private static final int SEED_WORD = 0x1f3d5b79;

	private static final int SEED_PAIR = 0x2e4c6a81;

	private static final int SEED_LETTERS = 0x3b5d7f93;

	private static final int SEED_SOUND = 0x4a6c8ea5;

	private static final int SEED_AMOUNT = 0x597b9db7;

	/** Stands for "some number" in word pairs, so "recharge 199" and "recharge 239" look alike. */
	private static final int NUMBER_HASH = 0x68aaacc9;

	private static final int BIAS_SLOT = Murmur3.hash("bias", 0x77b9bbdb) & (DIMENSION - 1);

	private static final double[] AMOUNT_BANDS = { 10, 20, 50, 100, 200, 500, 1000, 3000, 10000 };

	private final Weights weights;

	public FeatureExtractor() {
		this(Weights.DEFAULT);
	}

	public FeatureExtractor(Weights weights) {
		this.weights = weights;
	}

	public SparseVector extract(List<Token> tokens, BigDecimal amount) {
		SparseVector.Builder vector = new SparseVector.Builder();
		// always on: lets the model learn how common each category is
		vector.add(BIAS_SLOT, 1f);
		vector.closeGroup(1f);

		for (Token token : tokens) {
			if (token.isWord()) {
				add(vector, Murmur3.hash(token.text(), SEED_WORD));
			}
		}
		vector.closeGroup(weights.words());

		int previousHash = 0;
		boolean previousIsWord = false;
		boolean hasPrevious = false;
		for (Token token : tokens) {
			if (token.kind() == Token.Kind.STOP) {
				continue;
			}
			int hash = token.isNumber() ? NUMBER_HASH : Murmur3.hash(token.text(), SEED_WORD);
			if (hasPrevious && (previousIsWord || token.isWord())) {
				add(vector, Murmur3.combine(previousHash, hash, SEED_PAIR));
			}
			previousHash = hash;
			previousIsWord = token.isWord();
			hasPrevious = true;
		}
		vector.closeGroup(weights.pairs());

		for (Token token : tokens) {
			if (token.isWord()) {
				String marked = "^" + token.text() + "$";
				for (int n = 2; n <= 4; n++) {
					for (int i = 0; i + n <= marked.length(); i++) {
						add(vector, Murmur3.hash(marked, i, i + n, SEED_LETTERS + n));
					}
				}
			}
		}
		vector.closeGroup(weights.letters());

		for (Token token : tokens) {
			if (token.isWord() && token.text().length() >= 3 && isPlain(token.text())) {
				add(vector, Murmur3.hash(PhoneticKey.full(token.text()), SEED_SOUND));
			}
		}
		vector.closeGroup(weights.sound());

		if (amount != null && amount.signum() > 0) {
			add(vector, Murmur3.mix(SEED_AMOUNT + amountBand(amount.doubleValue())));
			vector.closeGroup(weights.amount());
		}
		return vector.build();
	}

	/** 0 for up to 10 rupees ... 9 for more than 10,000. */
	static int amountBand(double amount) {
		int band = 0;
		while (band < AMOUNT_BANDS.length && amount > AMOUNT_BANDS[band]) {
			band++;
		}
		return band;
	}

	/**
	 * The low bits pick the slot, another bit picks the sign. Signed hashing makes two features
	 * that collide in one slot cancel on average instead of adding up.
	 */
	private static void add(SparseVector.Builder vector, int hash) {
		vector.add(hash & (DIMENSION - 1), ((hash >>> 24) & 1) == 0 ? 1f : -1f);
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
