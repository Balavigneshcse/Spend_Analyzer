package com.spendanalyzer.ledger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;

import com.spendanalyzer.common.ApiException;
import com.spendanalyzer.common.Money;
import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.EntryType;
import com.spendanalyzer.model.PaymentMode;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Turns the raw text of a request into validated values, or throws a 400 that names the wrong
 * field. No database access: everything here can be checked before a transaction starts.
 */
@Component
public class EntryRequestParser {

	static final LocalDate EARLIEST_DATE = LocalDate.of(2000, 1, 1);

	private static final Pattern WHOLE_NUMBER = Pattern.compile("^\\d{1,7}$");

	private static final String NOT_USED = "This field is not used for this kind of entry.";

	private final Clock clock;

	private final ZoneId zone;

	public EntryRequestParser(Clock clock, @Qualifier("appZone") ZoneId zone) {
		this.clock = clock;
		this.zone = zone;
	}

	/** Today on the user's calendar (India), whatever zone the server runs in. */
	public LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), zone);
	}

	public NewEntry parseCreate(CreateEntryRequest request) {
		UUID clientId = Inputs.uuid(request.clientId())
			.orElseThrow(() -> ApiException.field("clientId", "clientId must be a UUID."));
		EntryType type = Inputs.enumValue("type", request.type(), EntryType.class);
		LocalDate date = Inputs.isBlank(request.date()) ? today() : entryDate(request.date());
		return switch (type) {
			case EXPENSE -> expense(clientId, date, request);
			case INCOME, CARD_PAYMENT -> {
				refuse("paymentMode", request.paymentMode());
				refuse("category", request.category());
				refuse("newBalance", request.newBalance());
				BigDecimal amount = Money.parseEntryAmount("amount", request.amount());
				AccountType account = Inputs.enumValue("account", request.account(), AccountType.class);
				if (account == AccountType.CREDIT_CARD) {
					throw ApiException.field("account", "Choose BANK or CASH.");
				}
				yield new NewEntry(clientId, type, date, amount, account, null,
						Inputs.optionalText("description", request.description()), null, null);
			}
			case WITHDRAWAL -> {
				refuse("paymentMode", request.paymentMode());
				refuse("category", request.category());
				refuse("newBalance", request.newBalance());
				BigDecimal amount = Money.parseEntryAmount("amount", request.amount());
				// An ATM withdrawal always comes out of the bank; stating that is fine, anything else is a mistake.
				if (!Inputs.isBlank(request.account())
						&& Inputs.enumValue("account", request.account(), AccountType.class) != AccountType.BANK) {
					throw ApiException.field("account", "A withdrawal always comes from the bank account.");
				}
				yield new NewEntry(clientId, type, date, amount, AccountType.BANK, null,
						Inputs.optionalText("description", request.description()), null, null);
			}
			case ADJUSTMENT -> {
				refuse("paymentMode", request.paymentMode());
				refuse("category", request.category());
				refuse("amount", request.amount());
				AccountType account = Inputs.enumValue("account", request.account(), AccountType.class);
				BigDecimal newBalance = Money.parseBalance("newBalance", request.newBalance());
				yield new NewEntry(clientId, type, date, null, account, null,
						Inputs.optionalText("description", request.description()), null, newBalance);
			}
		};
	}

	private NewEntry expense(UUID clientId, LocalDate date, CreateEntryRequest request) {
		refuse("newBalance", request.newBalance());
		String description = Inputs.requiredText("description", request.description(), "Type what you spent on.");
		BigDecimal amount = Money.parseEntryAmount("amount", request.amount());
		PaymentMode mode = Inputs.enumValue("paymentMode", request.paymentMode(), PaymentMode.class);
		Category category = optionalCategory(request.category());
		// The balance a spend touches is decided by the payment mode, never by the client.
		if (!Inputs.isBlank(request.account())
				&& Inputs.enumValue("account", request.account(), AccountType.class) != mode.account()) {
			throw ApiException.field("account", "The account follows from the payment mode. Leave it out.");
		}
		return new NewEntry(clientId, EntryType.EXPENSE, date, amount, mode.account(), mode, description, category,
				null);
	}

	public EntryChange parsePatch(PatchEntryRequest request) {
		String description = request.description() == null ? null
				: Inputs.requiredText("description", request.description(), "Type what you spent on.");
		BigDecimal amount = request.amount() == null ? null : Money.parseEntryAmount("amount", request.amount());
		PaymentMode mode = request.paymentMode() == null ? null
				: Inputs.enumValue("paymentMode", request.paymentMode(), PaymentMode.class);
		LocalDate date = Inputs.isBlank(request.date()) ? null : entryDate(request.date());
		return new EntryChange(description, amount, mode, date, optionalCategory(request.category()));
	}

	public OpeningBalances parseOpening(OpeningBalancesRequest request) {
		BigDecimal bank = Money.parseBalance("bank", request.bank());
		BigDecimal cash = Money.parseBalance("cash", request.cash());
		BigDecimal due = Inputs.isBlank(request.creditCardDue()) ? Money.ZERO
				: Money.parseBalance("creditCardDue", request.creditCardDue());
		return new OpeningBalances(bank, cash, due);
	}

	/** The text to classify for a live preview: required, cleaned, at most 120 characters. */
	public String parsePreviewText(PreviewRequest request) {
		return Inputs.requiredText("description", request.description(), "Type what you spent on.");
	}

	public EntryFilter parseFilter(String from, String to, String type, String mode, String category,
			String needsReview, String q) {
		LocalDate fromDate = Inputs.isBlank(from) ? null : Inputs.date("from", from);
		LocalDate toDate = Inputs.isBlank(to) ? null : Inputs.date("to", to);
		if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
			throw ApiException.field("to", "The end date is before the start date.");
		}
		String text = Inputs.clean(q);
		if (text.codePointCount(0, text.length()) > Inputs.MAX_DESCRIPTION) {
			throw ApiException.field("q", "Search for " + Inputs.MAX_DESCRIPTION + " characters or fewer.");
		}
		return new EntryFilter(fromDate, toDate,
				Inputs.isBlank(type) ? null : Inputs.enumValue("type", type, EntryType.class),
				Inputs.isBlank(mode) ? null : Inputs.enumValue("mode", mode, PaymentMode.class),
				Inputs.isBlank(category) ? null : Inputs.enumValue("category", category, Category.class),
				flag("needsReview", needsReview), text.isEmpty() ? null : text);
	}

	/** {@code size} above the maximum is cut down to it rather than refused. */
	public PageQuery parsePage(String page, String size) {
		int pageNumber = Inputs.isBlank(page) ? 0 : wholeNumber("page", page);
		int pageSize = Inputs.isBlank(size) ? PageQuery.DEFAULT_SIZE : wholeNumber("size", size);
		if (pageSize < 1) {
			throw ApiException.field("size", "size must be at least 1.");
		}
		return new PageQuery(pageNumber, Math.min(pageSize, PageQuery.MAX_SIZE));
	}

	private LocalDate entryDate(String raw) {
		LocalDate date = Inputs.date("date", raw);
		if (date.isBefore(EARLIEST_DATE)) {
			throw ApiException.field("date", "The date cannot be before " + EARLIEST_DATE + ".");
		}
		// One day of slack for a clock that runs ahead; anything later is a typo.
		if (date.isAfter(today().plusDays(1))) {
			throw ApiException.field("date", "The date cannot be later than tomorrow.");
		}
		return date;
	}

	private static Category optionalCategory(String raw) {
		return Inputs.isBlank(raw) ? null : Inputs.enumValue("category", raw, Category.class);
	}

	private static void refuse(String field, String raw) {
		if (!Inputs.isBlank(raw)) {
			throw ApiException.field(field, NOT_USED);
		}
	}

	private static boolean flag(String field, String raw) {
		if (Inputs.isBlank(raw) || raw.equals("false")) {
			return false;
		}
		if (raw.equals("true")) {
			return true;
		}
		throw ApiException.field(field, field + " must be true or false.");
	}

	private static int wholeNumber(String field, String raw) {
		if (!WHOLE_NUMBER.matcher(raw.trim()).matches()) {
			throw ApiException.field(field, field + " must be a whole number.");
		}
		return Integer.parseInt(raw.trim());
	}

}
