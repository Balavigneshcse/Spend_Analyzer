package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.spendanalyzer.ai.api.Prediction;
import com.spendanalyzer.common.ApiException;
import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.EntryType;
import com.spendanalyzer.model.PaymentMode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, edits and deletes ledger entries. Every method is one database transaction that
 * contains the entry row and the matching relative balance updates, so the balances always equal
 * the sum of the entries, also when requests overlap.
 * <p>
 * Lock order (never the other way round, so no deadlocks): edit and delete lock the entry row and
 * then the account rows; create locks the account rows and only then inserts. Account rows are
 * always taken in the order BANK, CASH, CREDIT_CARD.
 */
@Service
public class LedgerService {

	/** The largest number NUMERIC(12,2) holds; a bigger adjustment difference could not be stored. */
	private static final BigDecimal MAX_STORED = new BigDecimal("9999999999.99");

	/** @param created false when the client id was seen before and the earlier entry is returned */
	public record Saved(EntryView entry, BalancesView balances, boolean created) {
	}

	private final EntryRepository entries;

	private final AccountRepository accounts;

	private final SafeCategorizer ai;

	private final BalanceReader balances;

	private final Clock clock;

	public LedgerService(EntryRepository entries, AccountRepository accounts, SafeCategorizer ai, BalanceReader balances,
			Clock clock) {
		this.entries = entries;
		this.accounts = accounts;
		this.ai = ai;
		this.balances = balances;
		this.clock = clock;
	}

	@Transactional
	public Saved create(UUID userId, NewEntry request) {
		// Locking the balances first also lines up overlapping requests of one user.
		Map<AccountType, BigDecimal> current = accounts.lock(userId,
				LedgerEffects.touched(request.type(), request.account()));

		// Same client id again (double click, retry after a slow answer): answer with the first entry.
		Optional<EntryRow> earlier = entries.findByClientId(userId, request.clientId());
		if (earlier.isPresent()) {
			return saved(userId, earlier.get(), false);
		}

		EntryRow row = request.type() == EntryType.EXPENSE ? expenseRow(userId, request)
				: plainRow(userId, request, current);
		if (!entries.insertIfAbsent(row)) {
			// A request with the same client id but another account slipped in between the check and the insert.
			EntryRow winner = entries.findByClientId(userId, request.clientId())
				.orElseThrow(() -> ApiException.conflict("conflict", "Please try again."));
			return saved(userId, winner, false);
		}
		accounts.apply(userId, row.effects());
		if (request.category() != null) {
			// Only a category the user picked is a lesson; the AI's own guess is never fed back to it.
			ai.learn(userId, row.description(), request.category(), row.aiCategory());
		}
		return saved(userId, row, true);
	}

	@Transactional(readOnly = true)
	public EntryView get(UUID userId, UUID entryId) {
		return entries.find(userId, entryId).map(EntryView::of).orElseThrow(LedgerService::noSuchEntry);
	}

	/** Edits an expense and moves the balances by exactly the difference between the old and the new version. */
	@Transactional
	public Saved edit(UUID userId, UUID entryId, EntryChange change) {
		EntryRow old = entries.lock(userId, entryId).orElseThrow(LedgerService::noSuchEntry);
		if (old.type() != EntryType.EXPENSE) {
			throw ApiException.badRequest("validation", "Only spends can be edited. Delete this entry and add it again.");
		}
		PaymentMode mode = change.paymentMode() != null ? change.paymentMode() : old.paymentMode();
		BigDecimal amount = change.amount() != null ? change.amount() : old.amount();
		String description = change.description() != null ? change.description() : old.description();
		boolean textChanged = !description.equals(old.description());

		// What the AI says about the text: asked again only when the text changed.
		Category aiCategory = old.aiCategory();
		String item = old.item();
		Float aiConfidence = old.aiConfidence();
		String aiSource = old.aiSource();
		Category category = old.category();
		boolean needsReview = old.needsReview();
		boolean userCorrected = old.userCorrected();
		if (textChanged) {
			Prediction prediction = ai.predict(userId, description, amount, mode);
			aiCategory = prediction.category();
			item = prediction.item();
			aiConfidence = (float) prediction.confidence();
			aiSource = prediction.source().name();
			if (!old.userCorrected()) {
				category = prediction.category();
				needsReview = prediction.needsReview();
			}
		}
		if (change.category() != null) {
			category = change.category();
			userCorrected = true;
			needsReview = false;
			ai.learn(userId, description, category, aiCategory);
		}

		EntryRow updated = new EntryRow(old.id(), userId, old.clientId(), EntryType.EXPENSE, amount, mode.account(),
				mode, description, category, item, aiCategory, aiConfidence, aiSource, needsReview, userCorrected,
				change.date() != null ? change.date() : old.date(), old.createdAt(), now());
		// Undo the old effect and apply the new one. UPI -> CASH gives +bank and -cash in one transaction.
		accounts.apply(userId, LedgerEffects.replace(old.effects(), updated.effects()));
		entries.update(updated);
		return saved(userId, updated, false);
	}

	/** Deletes an entry of any type and takes its effect back out of the balances. */
	@Transactional
	public BalancesView delete(UUID userId, UUID entryId) {
		// Of two simultaneous deletes the second waits here and then finds nothing: 404, no double refund.
		EntryRow row = entries.lock(userId, entryId).orElseThrow(LedgerService::noSuchEntry);
		accounts.apply(userId, LedgerEffects.inverse(row.effects()));
		entries.delete(userId, entryId);
		return balances.read(userId);
	}

	private EntryRow expenseRow(UUID userId, NewEntry request) {
		// The AI always runs, also when the user picked the category: item and ai_* columns record its opinion.
		Prediction prediction = ai.predict(userId, request.description(), request.amount(), request.paymentMode());
		boolean chosenByUser = request.category() != null;
		Instant now = now();
		return new EntryRow(UUID.randomUUID(), userId, request.clientId(), EntryType.EXPENSE, request.amount(),
				request.paymentMode().account(), request.paymentMode(), request.description(),
				chosenByUser ? request.category() : prediction.category(), prediction.item(), prediction.category(),
				(float) prediction.confidence(), prediction.source().name(),
				chosenByUser ? false : prediction.needsReview(), chosenByUser, request.date(), now, now);
	}

	private EntryRow plainRow(UUID userId, NewEntry request, Map<AccountType, BigDecimal> lockedBalances) {
		BigDecimal amount = request.amount();
		if (request.type() == EntryType.ADJUSTMENT) {
			// The row is locked, so "current" cannot change before the difference is applied.
			amount = request.newBalance().subtract(lockedBalances.get(request.account()));
			if (amount.signum() == 0) {
				throw ApiException.field("newBalance", "That is already the balance. Nothing to change.");
			}
			if (amount.abs().compareTo(MAX_STORED) > 0) {
				throw ApiException.field("newBalance", "That change is too large.");
			}
		}
		Instant now = now();
		return new EntryRow(UUID.randomUUID(), userId, request.clientId(), request.type(), amount, request.account(),
				null, request.description(), null, null, null, null, null, false, false, request.date(), now, now);
	}

	private Saved saved(UUID userId, EntryRow row, boolean created) {
		return new Saved(EntryView.of(row), balances.read(userId), created);
	}

	/** Microseconds, like the database column, so the answer of a create equals a later read. */
	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private static ApiException noSuchEntry() {
		return ApiException.notFound("No such entry.");
	}

}
