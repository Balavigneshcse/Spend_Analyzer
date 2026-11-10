package com.spendanalyzer.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import com.spendanalyzer.common.ApiException;
import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.EntryType;
import com.spendanalyzer.model.PaymentMode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.http.HttpStatus;

/** Input rules without Spring or a database. The clock stands still at 01:30 on 11 Nov 2026 in India. */
class EntryRequestParserTest {

	/** 20:00 UTC on the 10th is already the 11th in India: proves "today" uses the app zone. */
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T20:00:00Z"), ZoneOffset.UTC);

	private static final String CLIENT_ID = "0b0f6a52-8e5c-4d59-9a53-2f0f7c1f3a11";

	private final EntryRequestParser parser = new EntryRequestParser(CLOCK, ZoneId.of("Asia/Kolkata"));

	private static CreateEntryRequest expense(String description, String amount, String mode, String category,
			String date) {
		return new CreateEntryRequest(CLIENT_ID, "EXPENSE", date, description, amount, mode, category, null, null);
	}

	private static void assertRejected(String field, ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(ex.code()).isEqualTo("validation");
			assertThat(ex.fields()).containsOnlyKeys(field);
		});
	}

	// ---------------------------------------------------------------- expense

	@Test
	void expenseIsParsedAndTheAccountComesFromThePaymentMode() {
		NewEntry entry = parser.parseCreate(expense("  juse ", "40", "UPI", null, null));
		assertThat(entry.clientId()).isEqualTo(UUID.fromString(CLIENT_ID));
		assertThat(entry.type()).isEqualTo(EntryType.EXPENSE);
		assertThat(entry.description()).isEqualTo("juse");
		assertThat(entry.amount()).isEqualTo(new BigDecimal("40.00"));
		assertThat(entry.paymentMode()).isEqualTo(PaymentMode.UPI);
		assertThat(entry.account()).isEqualTo(AccountType.BANK);
		assertThat(entry.category()).isNull();
		assertThat(entry.date()).isEqualTo(LocalDate.of(2026, 11, 11));
		assertThat(parser.parseCreate(expense("tea", "10", "CASH", "SNACKS_BEVERAGES", null)).account())
			.isEqualTo(AccountType.CASH);
		assertThat(parser.parseCreate(expense("shoes", "999.5", "CREDIT_CARD", "SHOPPING", null)).account())
			.isEqualTo(AccountType.CREDIT_CARD);
	}

	@Test
	void anExpenseCannotChooseItsAccount() {
		CreateEntryRequest wrong = new CreateEntryRequest(CLIENT_ID, "EXPENSE", null, "juse", "40", "UPI", null,
				"CREDIT_CARD", null);
		assertRejected("account", () -> parser.parseCreate(wrong));
		// stating the account the mode implies anyway is harmless
		CreateEntryRequest same = new CreateEntryRequest(CLIENT_ID, "EXPENSE", null, "juse", "40", "UPI", null, "BANK",
				null);
		assertThat(parser.parseCreate(same).account()).isEqualTo(AccountType.BANK);
	}

	@ParameterizedTest
	@ValueSource(strings = { "-5", "0", "0.00", "1e9", "12.345", "NaN", "1,000", "", " ", "+5", "Infinity", "1000000.01",
			"12345678901", "٤٠", "4 0", "40.", ".5", "0x10" })
	void badAmountsAreRefusedNotRounded(String amount) {
		assertRejected("amount", () -> parser.parseCreate(expense("juse", amount, "UPI", null, null)));
	}

	@Test
	void missingAmountIsRefused() {
		assertRejected("amount", () -> parser.parseCreate(expense("juse", null, "UPI", null, null)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0.01", "40", "49.5", "49.50", "1000000", "1000000.00" })
	void goodAmountsAreAccepted(String amount) {
		assertThat(parser.parseCreate(expense("juse", amount, "UPI", null, null)).amount())
			.isEqualByComparingTo(new BigDecimal(amount));
	}

	@Test
	void enumsAreStrict() {
		assertRejected("paymentMode", () -> parser.parseCreate(expense("juse", "40", "upi", null, null)));
		assertRejected("paymentMode", () -> parser.parseCreate(expense("juse", "40", "CARD", null, null)));
		assertRejected("paymentMode", () -> parser.parseCreate(expense("juse", "40", null, null, null)));
		assertRejected("category", () -> parser.parseCreate(expense("juse", "40", "UPI", "FOOD", null)));
		assertRejected("type", () -> parser
			.parseCreate(new CreateEntryRequest(CLIENT_ID, "SPEND", null, "juse", "40", "UPI", null, null, null)));
		assertRejected("type", () -> parser
			.parseCreate(new CreateEntryRequest(CLIENT_ID, null, null, "juse", "40", "UPI", null, null, null)));
	}

	@Test
	void clientIdMustBeAUuid() {
		for (String bad : new String[] { null, "", "abc", "1-1-1-1-1", CLIENT_ID + "0", "{" + CLIENT_ID + "}" }) {
			assertRejected("clientId", () -> parser
				.parseCreate(new CreateEntryRequest(bad, "EXPENSE", null, "juse", "40", "UPI", null, null, null)));
		}
	}

	// ---------------------------------------------------------------- description

	@Test
	void descriptionIsTrimmedAndControlCharactersAreStripped() {
		assertThat(parser.parseCreate(expense("\u0000 bus\tticket\u0007 \n", "20", "UPI", null, null)).description())
			.isEqualTo("bus ticket");
		assertRejected("description", () -> parser.parseCreate(expense("  \u0000\t ", "20", "UPI", null, null)));
		assertRejected("description", () -> parser.parseCreate(expense(null, "20", "UPI", null, null)));
	}

	@Test
	void descriptionKeepsMarkupAndOtherLanguagesExactly() {
		String html = "<img src=x onerror=alert(1)> & \"quotes\" 'single'";
		assertThat(parser.parseCreate(expense(html, "20", "UPI", null, null)).description()).isEqualTo(html);
		assertThat(parser.parseCreate(expense("பிரியாணி 🍛", "20", "UPI", null, null)).description())
			.isEqualTo("பிரியாணி 🍛");
	}

	@Test
	void descriptionMayHave120CharactersButNotMore() {
		assertThat(parser.parseCreate(expense("x".repeat(120), "20", "UPI", null, null)).description()).hasSize(120);
		assertRejected("description", () -> parser.parseCreate(expense("x".repeat(121), "20", "UPI", null, null)));
		// an emoji is one character even though Java stores it as two
		assertThat(parser.parseCreate(expense("🍛".repeat(120), "20", "UPI", null, null)).description())
			.isEqualTo("🍛".repeat(120));
		assertRejected("description", () -> parser.parseCreate(expense("🍛".repeat(121), "20", "UPI", null, null)));
	}

	// ---------------------------------------------------------------- date

	@Test
	void dateMayBeTomorrowButNotLaterAndNotBefore2000() {
		assertThat(parser.parseCreate(expense("juse", "40", "UPI", null, "2026-11-12")).date())
			.isEqualTo(LocalDate.of(2026, 11, 12));
		assertThat(parser.parseCreate(expense("juse", "40", "UPI", null, "2000-01-01")).date())
			.isEqualTo(LocalDate.of(2000, 1, 1));
		assertRejected("date", () -> parser.parseCreate(expense("juse", "40", "UPI", null, "2026-11-13")));
		assertRejected("date", () -> parser.parseCreate(expense("juse", "40", "UPI", null, "1999-12-31")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "10-11-2026", "2026/11/10", "2026-02-30", "2026-13-01", "2026-11-10T10:00:00Z", "today",
			"20261110", "+2026-11-10" })
	void malformedDatesAreRefused(String date) {
		assertRejected("date", () -> parser.parseCreate(expense("juse", "40", "UPI", null, date)));
	}

	// ---------------------------------------------------------------- other types

	@Test
	void incomeAndCardPaymentNeedBankOrCash() {
		NewEntry income = parser.parseCreate(
				new CreateEntryRequest(CLIENT_ID, "INCOME", null, " pocket money ", "500", null, null, "CASH", null));
		assertThat(income.account()).isEqualTo(AccountType.CASH);
		assertThat(income.description()).isEqualTo("pocket money");
		assertThat(income.paymentMode()).isNull();
		for (String type : new String[] { "INCOME", "CARD_PAYMENT" }) {
			assertRejected("account", () -> parser
				.parseCreate(new CreateEntryRequest(CLIENT_ID, type, null, null, "500", null, null, "CREDIT_CARD", null)));
			assertRejected("account", () -> parser
				.parseCreate(new CreateEntryRequest(CLIENT_ID, type, null, null, "500", null, null, null, null)));
			assertRejected("paymentMode", () -> parser
				.parseCreate(new CreateEntryRequest(CLIENT_ID, type, null, null, "500", "UPI", null, "BANK", null)));
			assertRejected("category", () -> parser
				.parseCreate(new CreateEntryRequest(CLIENT_ID, type, null, null, "500", null, "SHOPPING", "BANK", null)));
		}
	}

	@Test
	void withdrawalAlwaysUsesTheBank() {
		NewEntry entry = parser
			.parseCreate(new CreateEntryRequest(CLIENT_ID, "WITHDRAWAL", null, null, "500", null, null, null, null));
		assertThat(entry.account()).isEqualTo(AccountType.BANK);
		assertThat(entry.description()).isNull();
		assertRejected("account", () -> parser
			.parseCreate(new CreateEntryRequest(CLIENT_ID, "WITHDRAWAL", null, null, "500", null, null, "CASH", null)));
	}

	@Test
	void adjustmentTakesAnAccountAndANewBalance() {
		NewEntry entry = parser
			.parseCreate(new CreateEntryRequest(CLIENT_ID, "ADJUSTMENT", null, null, null, null, null, "CREDIT_CARD", "0"));
		assertThat(entry.account()).isEqualTo(AccountType.CREDIT_CARD);
		assertThat(entry.newBalance()).isEqualTo(new BigDecimal("0.00"));
		assertThat(entry.amount()).isNull();
		assertRejected("newBalance", () -> parser
			.parseCreate(new CreateEntryRequest(CLIENT_ID, "ADJUSTMENT", null, null, null, null, null, "BANK", "-1")));
		assertRejected("newBalance", () -> parser
			.parseCreate(new CreateEntryRequest(CLIENT_ID, "ADJUSTMENT", null, null, null, null, null, "BANK", null)));
		assertRejected("newBalance", () -> parser.parseCreate(
				new CreateEntryRequest(CLIENT_ID, "ADJUSTMENT", null, null, null, null, null, "BANK", "1000000000")));
		assertRejected("amount", () -> parser
			.parseCreate(new CreateEntryRequest(CLIENT_ID, "ADJUSTMENT", null, null, "5", null, null, "BANK", "10")));
		assertRejected("newBalance", () -> parser
			.parseCreate(new CreateEntryRequest(CLIENT_ID, "EXPENSE", null, "juse", "5", "UPI", null, null, "10")));
	}

	// ---------------------------------------------------------------- patch, opening, list

	@Test
	void patchKeepsWhatIsNotSent() {
		EntryChange nothing = parser.parsePatch(new PatchEntryRequest(null, null, null, null, null));
		assertThat(nothing).isEqualTo(new EntryChange(null, null, null, null, null));
		EntryChange all = parser.parsePatch(new PatchEntryRequest(" tea ", "12.5", "CASH", "2026-11-01", "SNACKS_BEVERAGES"));
		assertThat(all).isEqualTo(new EntryChange("tea", new BigDecimal("12.50"), PaymentMode.CASH,
				LocalDate.of(2026, 11, 1), Category.SNACKS_BEVERAGES));
		assertRejected("description", () -> parser.parsePatch(new PatchEntryRequest(" ", null, null, null, null)));
		assertRejected("amount", () -> parser.parsePatch(new PatchEntryRequest(null, "0", null, null, null)));
		assertRejected("paymentMode", () -> parser.parsePatch(new PatchEntryRequest(null, null, "PAYTM", null, null)));
		assertRejected("date", () -> parser.parsePatch(new PatchEntryRequest(null, null, null, "2030-01-01", null)));
		assertRejected("category", () -> parser.parsePatch(new PatchEntryRequest(null, null, null, null, "food")));
	}

	@Test
	void openingBalancesAllowZeroAndDefaultTheCardDue() {
		OpeningBalances opening = parser.parseOpening(new OpeningBalancesRequest("5200", "0", null));
		assertThat(opening).isEqualTo(
				new OpeningBalances(new BigDecimal("5200.00"), new BigDecimal("0.00"), new BigDecimal("0.00")));
		assertRejected("bank", () -> parser.parseOpening(new OpeningBalancesRequest("-1", "0", null)));
		assertRejected("bank", () -> parser.parseOpening(new OpeningBalancesRequest(null, "0", null)));
		assertRejected("cash", () -> parser.parseOpening(new OpeningBalancesRequest("1", "1.999", null)));
		assertRejected("creditCardDue", () -> parser.parseOpening(new OpeningBalancesRequest("1", "1", "1e3")));
	}

	@Test
	void listFiltersAndPaging() {
		EntryFilter filter = parser.parseFilter("2026-11-01", "2026-11-10", "EXPENSE", "UPI", "SHOPPING", "true", " Tea ");
		assertThat(filter).isEqualTo(new EntryFilter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10),
				EntryType.EXPENSE, PaymentMode.UPI, Category.SHOPPING, true, "Tea"));
		assertThat(parser.parseFilter(null, null, null, null, null, null, null)).isEqualTo(EntryFilter.none());
		assertThat(parser.parseFilter("", "", "", "", "", "false", " ")).isEqualTo(EntryFilter.none());
		assertRejected("from", () -> parser.parseFilter("yesterday", null, null, null, null, null, null));
		assertRejected("to", () -> parser.parseFilter("2026-11-10", "2026-11-01", null, null, null, null, null));
		assertRejected("type", () -> parser.parseFilter(null, null, "expense", null, null, null, null));
		assertRejected("mode", () -> parser.parseFilter(null, null, null, "GPAY", null, null, null));
		assertRejected("category", () -> parser.parseFilter(null, null, null, null, "X", null, null));
		assertRejected("needsReview", () -> parser.parseFilter(null, null, null, null, null, "yes", null));
		assertRejected("q", () -> parser.parseFilter(null, null, null, null, null, null, "x".repeat(121)));

		assertThat(parser.parsePage(null, null)).isEqualTo(new PageQuery(0, 20));
		assertThat(parser.parsePage("3", "50")).isEqualTo(new PageQuery(3, 50));
		assertThat(parser.parsePage("0", "5000")).isEqualTo(new PageQuery(0, 100));
		assertThat(new PageQuery(9_999_999, 100).offset()).isEqualTo(999_999_900L);
		assertRejected("page", () -> parser.parsePage("-1", null));
		assertRejected("page", () -> parser.parsePage("1.5", null));
		assertRejected("page", () -> parser.parsePage("99999999", null));
		assertRejected("size", () -> parser.parsePage(null, "0"));
		assertRejected("size", () -> parser.parsePage(null, "ten"));
	}

}
