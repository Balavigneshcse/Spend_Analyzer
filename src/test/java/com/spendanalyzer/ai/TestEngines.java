package com.spendanalyzer.ai;

import com.spendanalyzer.ai.engine.CategorizerEngine;
import com.spendanalyzer.ai.learn.InMemoryUserMemoryStore;
import com.spendanalyzer.ai.learn.UserMemoryStore;

/**
 * One trained engine for all plain unit tests. Loading the word lists and training takes about a
 * second, so it is done once per test run; the engine is immutable after that, which makes sharing
 * it between test classes safe.
 */
final class TestEngines {

	private static CategorizerEngine trained;

	private TestEngines() {
	}

	/** The full engine (word lists + trained model), without any user memory. */
	static synchronized CategorizerEngine full() {
		if (trained == null) {
			CategorizerEngine engine = CategorizerEngine.create(UserMemoryStore.NONE);
			engine.trainNow();
			trained = engine;
		}
		return trained;
	}

	static CategorizerEngine lexiconOnly() {
		return full().variant(CategorizerEngine.Options.LEXICON_ONLY, UserMemoryStore.NONE);
	}

	static CategorizerEngine modelOnly() {
		return full().variant(CategorizerEngine.Options.MODEL_ONLY, UserMemoryStore.NONE);
	}

	/** The full engine with a fresh, empty in-memory user memory. */
	static CategorizerEngine withMemory(InMemoryUserMemoryStore store) {
		return full().variant(CategorizerEngine.Options.FULL, store);
	}

}
