package com.spendanalyzer.ledger;

import java.util.List;
import java.util.UUID;

import com.spendanalyzer.common.ApiException;
import com.spendanalyzer.security.AuthUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/entries}: the ledger of the logged-in user. The user id is taken from the session
 * and nowhere else; an entry of somebody else looks exactly like one that does not exist (404).
 */
@RestController
@RequestMapping("/api/entries")
public class EntriesController {

	public record EntryAndBalances(EntryView entry, BalancesView balances) {
	}

	public record EntryOnly(EntryView entry) {
	}

	public record BalancesOnly(BalancesView balances) {
	}

	public record EntryPage(List<EntryView> items, int page, int size, long total, boolean hasMore) {
	}

	private final LedgerService ledger;

	private final EntryQueries queries;

	private final EntryRequestParser parser;

	public EntriesController(LedgerService ledger, EntryQueries queries, EntryRequestParser parser) {
		this.ledger = ledger;
		this.queries = queries;
		this.parser = parser;
	}

	/** The AI's guess while the user is still typing. Saves nothing and teaches the AI nothing. */
	@PostMapping("/preview")
	public PreviewView preview(@AuthenticationPrincipal AuthUser user, @RequestBody PreviewRequest body) {
		return queries.preview(user.id(), parser.parsePreviewText(body), body.amount(), body.paymentMode());
	}

	/** 201 for a new entry, 200 when the same clientId was sent before (the first entry is returned). */
	@PostMapping
	public ResponseEntity<EntryAndBalances> create(@AuthenticationPrincipal AuthUser user,
			@RequestBody CreateEntryRequest body) {
		LedgerService.Saved saved = ledger.create(user.id(), parser.parseCreate(body));
		return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(new EntryAndBalances(saved.entry(), saved.balances()));
	}

	@GetMapping
	public EntryPage list(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String type,
			@RequestParam(required = false) String mode, @RequestParam(required = false) String category,
			@RequestParam(required = false) String needsReview, @RequestParam(required = false) String q,
			@RequestParam(required = false) String page, @RequestParam(required = false) String size) {
		EntryFilter filter = parser.parseFilter(from, to, type, mode, category, needsReview, q);
		return queries.page(user.id(), filter, parser.parsePage(page, size));
	}

	@GetMapping("/{id}")
	public EntryOnly get(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
		return new EntryOnly(ledger.get(user.id(), entryId(id)));
	}

	@PatchMapping("/{id}")
	public EntryAndBalances edit(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
			@RequestBody PatchEntryRequest body) {
		LedgerService.Saved saved = ledger.edit(user.id(), entryId(id), parser.parsePatch(body));
		return new EntryAndBalances(saved.entry(), saved.balances());
	}

	@DeleteMapping("/{id}")
	public BalancesOnly delete(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
		return new BalancesOnly(ledger.delete(user.id(), entryId(id)));
	}

	/** An id that is not even a UUID cannot belong to an entry: plain 404, same as an unknown id. */
	private static UUID entryId(String raw) {
		return Inputs.uuid(raw).orElseThrow(() -> ApiException.notFound("No such entry."));
	}

}
