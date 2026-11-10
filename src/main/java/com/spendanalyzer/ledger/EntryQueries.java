package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.spendanalyzer.common.ApiException;
import com.spendanalyzer.common.Money;
import com.spendanalyzer.model.PaymentMode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The read-only side of the ledger: the paged list and the live AI preview. */
@Service
public class EntryQueries {

	private final EntryRepository entries;

	private final SafeCategorizer ai;

	public EntryQueries(EntryRepository entries, SafeCategorizer ai) {
		this.entries = entries;
		this.ai = ai;
	}

	@Transactional(readOnly = true)
	public EntriesController.EntryPage page(UUID userId, EntryFilter filter, PageQuery page) {
		long total = entries.count(userId, filter);
		List<EntryView> items = page.offset() >= total ? List.of()
				: entries.search(userId, filter, page.size(), page.offset()).stream().map(EntryView::of).toList();
		boolean hasMore = page.offset() + items.size() < total;
		return new EntriesController.EntryPage(items, page.page(), page.size(), total, hasMore);
	}

	/**
	 * Classifies a text without saving anything. Amount and payment mode are only weak hints and
	 * the user may still be typing them, so a half-typed value is ignored instead of refused.
	 */
	public PreviewView preview(UUID userId, String text, String rawAmount, String rawMode) {
		return PreviewView.of(ai.predict(userId, text, hintAmount(rawAmount), hintMode(rawMode)));
	}

	private static BigDecimal hintAmount(String raw) {
		if (Inputs.isBlank(raw)) {
			return null;
		}
		try {
			return Money.parseEntryAmount("amount", raw);
		}
		catch (ApiException ex) {
			return null;
		}
	}

	private static PaymentMode hintMode(String raw) {
		for (PaymentMode mode : PaymentMode.values()) {
			if (mode.name().equals(raw)) {
				return mode;
			}
		}
		return null;
	}

}
