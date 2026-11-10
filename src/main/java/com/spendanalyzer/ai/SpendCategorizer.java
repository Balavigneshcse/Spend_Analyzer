package com.spendanalyzer.ai;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import com.spendanalyzer.ai.api.Categorizer;
import com.spendanalyzer.ai.api.Prediction;
import com.spendanalyzer.ai.engine.CategorizerEngine;
import com.spendanalyzer.ai.learn.JdbcUserMemoryStore;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The application's {@link Categorizer}: the plain-Java {@link CategorizerEngine} wired to the
 * database for per-user memory.
 *
 * <p>Start-up cost matters (the server is tiny), so the constructor only reads the word lists.
 * The model is trained on a background thread once the application is up; until it is ready the
 * word lists and the spelling repair answer on their own, which already covers most texts.
 *
 * <p>Settings (all optional):
 * <ul>
 * <li>{@code app.ai.training}: {@code background} (default), {@code startup} (train inside the
 * constructor; the default under the "test" profile so every test sees the same answers) or
 * {@code off} (word lists only)</li>
 * <li>{@code app.ai.max-user-texts}, {@code app.ai.max-user-words}: caps on what is stored per
 * user</li>
 * </ul>
 */
@Component
public class SpendCategorizer implements Categorizer {

	private static final Logger log = LoggerFactory.getLogger(SpendCategorizer.class);

	/** When the model is trained. */
	enum Training {

		BACKGROUND, STARTUP, OFF

	}

	private final CategorizerEngine engine;

	private final Training training;

	public SpendCategorizer(JdbcClient jdbc, Clock clock, Environment environment) {
		int maxTexts = environment.getProperty("app.ai.max-user-texts", Integer.class,
				JdbcUserMemoryStore.DEFAULT_MAX_TEXTS);
		int maxWords = environment.getProperty("app.ai.max-user-words", Integer.class,
				JdbcUserMemoryStore.DEFAULT_MAX_WORD_ROWS);
		this.training = training(environment);
		this.engine = CategorizerEngine.create(new JdbcUserMemoryStore(jdbc, clock, maxTexts, maxWords));
		CategorizerEngine.Stats stats = engine.stats();
		log.info("AI word lists loaded in {} ms: {} items, {} spellings", stats.loadMillis(), stats.items(),
				stats.terms());
		if (training == Training.STARTUP) {
			engine.trainNow();
		}
	}

	private static Training training(Environment environment) {
		// Tests must not depend on how far a background thread has got, so they train up front.
		String fallback = environment.matchesProfiles("test") ? "startup" : "background";
		String value = environment.getProperty("app.ai.training", fallback).trim();
		try {
			return Training.valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("app.ai.training must be background, startup or off, not '" + value + "'");
		}
	}

	/** Background training starts only when the application is ready, so it never slows the start. */
	@EventListener(ApplicationReadyEvent.class)
	public void trainInBackground() {
		if (training == Training.BACKGROUND) {
			engine.startTraining();
		}
	}

	@Override
	public Prediction categorize(UUID userId, String text, BigDecimal amount, PaymentMode mode) {
		if (training == Training.BACKGROUND && !engine.isModelReady()) {
			// covers contexts that never publish ApplicationReadyEvent; a no-op once started
			engine.startTraining();
		}
		// the payment mode says next to nothing about the category, so it is deliberately unused
		return engine.categorize(userId, text, amount);
	}

	@Override
	public void learn(UUID userId, String text, Category finalCategory, boolean wasCorrection) {
		engine.learn(userId, text, finalCategory, wasCorrection);
	}

	/**
	 * Blocks until the model is trained. For tests and warm-up.
	 * @return false when the time ran out, training failed or training is switched off
	 */
	public boolean awaitModel(Duration timeout) {
		if (training == Training.OFF) {
			return false;
		}
		engine.startTraining();
		return engine.awaitModel(timeout);
	}

	public boolean isModelReady() {
		return engine.isModelReady();
	}

	public CategorizerEngine.Stats stats() {
		return engine.stats();
	}

}
