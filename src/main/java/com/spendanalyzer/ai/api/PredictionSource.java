package com.spendanalyzer.ai.api;

/** Which part of the classifier produced a prediction. Stored in {@code entries.ai_source}. */
public enum PredictionSource {

	/** The user taught this exact text before. */
	USER_MEMORY,
	/** A known word or phrase matched exactly. */
	LEXICON_EXACT,
	/** A known word matched after spelling / sound correction. */
	LEXICON_FUZZY,
	/** The trained model decided from letter and word patterns. */
	MODEL,
	/** Word list and model agreed or were combined. */
	ENSEMBLE,
	/** Not confident enough: category OTHER, needs review. */
	ABSTAIN

}
