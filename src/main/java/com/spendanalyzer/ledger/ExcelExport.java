package com.spendanalyzer.ledger;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import com.spendanalyzer.common.Money;
import com.spendanalyzer.ledger.excel.XlsxWriter;
import com.spendanalyzer.ledger.excel.XlsxWriter.Sheet;
import com.spendanalyzer.ledger.excel.XlsxWriter.Style;
import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.EntryType;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Lays out a user's {@link ExportService.Snapshot} as an Excel workbook: a summary, every entry, and
 * the friends still owed money. The words and signs follow the transaction list of the page
 * (js/entries.js), so the file reads like the app.
 */
@Component
public class ExcelExport {

	static final String SUMMARY = "Summary";

	static final String TRANSACTIONS = "Transactions";

	static final String FRIENDS = "Friends you owe";

	static final List<String> SHEETS = List.of(SUMMARY, TRANSACTIONS, FRIENDS);

	static final String[] TRANSACTION_COLUMNS = { "Date", "Type", "What", "Category", "Paid by / Account", "Amount",
			"Friend", "Note", "Needs checking", "Added on" };

	static final String[] FRIEND_COLUMNS = { "Friend", "Owed", "Borrowed", "Paid back", "Last date" };

	/** The description the app gives the entries made by the first balance setup. */
	private static final String OPENING = BalanceService.OPENING_DESCRIPTION;

	private final ZoneId zone;

	public ExcelExport(@Qualifier("appZone") ZoneId zone) {
		this.zone = zone;
	}

	/**
	 * Streams the workbook to {@code out}; {@code out} stays open. If writing fails half way, the
	 * file is left without its end, so it cannot pass as a whole but shorter workbook.
	 */
	public void write(ExportService.Snapshot data, OutputStream out) throws IOException {
		XlsxWriter book = new XlsxWriter(out, SHEETS);
		try {
			summary(book.startSheet(false, 26, 34), data);
			transactions(book.startSheet(true, 13, 18, 34, 22, 18, 14, 18, 30, 15, 20), data.entries());
			// a plain sheet for the one sentence when nobody is owed: no frozen row, no filter buttons
			friends(book.startSheet(!data.debts().people().isEmpty(), 26, 14, 14, 14, 13), data.debts());
		}
		catch (IOException | RuntimeException | Error failure) {
			book.abort();
			throw failure;
		}
		book.close();
	}

	private void summary(Sheet sheet, ExportService.Snapshot data) throws IOException {
		BalancesView balances = data.balances();
		sheet.row().text("Your Spend Analyzer data", Style.BOLD);
		sheet.row();
		sheet.row().text("Exported on", Style.BOLD).dateTime(local(data.exportedAt()));
		sheet.row().text("Name", Style.BOLD).text(data.user().name());
		sheet.row().text("Email", Style.BOLD).text(data.user().email());
		sheet.row().text("Phone", Style.BOLD).text(data.user().phoneMasked());
		sheet.row();
		sheet.row().text("Bank balance", Style.BOLD).money(amount(balances.bank()));
		sheet.row().text("Cash in hand", Style.BOLD).money(amount(balances.cash()));
		sheet.row().text("Credit card due", Style.BOLD).money(amount(balances.creditCardDue()));
		sheet.row().text("Total money you have", Style.BOLD).money(amount(balances.available()), Style.BOLD_MONEY);
		sheet.row().text("Owed to friends", Style.BOLD).money(amount(balances.owedToFriends()));
		sheet.row();
		sheet.row().text("Entries in this file", Style.BOLD).number(data.entries().size());
		if (data.cut()) {
			sheet.row().text("Only your newest " + String.format(Locale.ROOT, "%,d", ExportService.MAX_ENTRIES)
					+ " entries are included.");
		}
	}

	private void transactions(Sheet sheet, List<EntryRow> entries) throws IOException {
		sheet.header(TRANSACTION_COLUMNS);
		for (EntryRow entry : entries) {
			boolean borrowing = entry.type() == EntryType.BORROW || entry.type() == EntryType.REPAY;
			sheet.row()
				.date(entry.date())
				.text(typeLabel(entry))
				.text(what(entry))
				.text(entry.type() == EntryType.EXPENSE && entry.category() != null ? entry.category().label() : null)
				.text(paidBy(entry))
				.money(signedAmount(entry))
				.text(borrowing ? entry.person() : null)
				.text(borrowing ? entry.description() : null)
				.text(entry.needsReview() ? "Yes" : null)
				.dateTime(local(entry.createdAt()));
		}
	}

	private static void friends(Sheet sheet, DebtsView debts) throws IOException {
		if (debts.people().isEmpty()) {
			sheet.row().text("You do not owe any friend money.");
			return;
		}
		sheet.header(FRIEND_COLUMNS);
		BigDecimal borrowed = Money.ZERO;
		BigDecimal repaid = Money.ZERO;
		for (DebtsView.Person person : debts.people()) {
			BigDecimal personBorrowed = amount(person.borrowed());
			BigDecimal personRepaid = amount(person.repaid());
			borrowed = borrowed.add(personBorrowed);
			repaid = repaid.add(personRepaid);
			sheet.row()
				.text(person.name())
				.money(amount(person.owed()))
				.money(personBorrowed)
				.money(personRepaid)
				.date(LocalDate.parse(person.lastDate()));
		}
		// the filter stops at the last friend, so sorting never moves Total in among them; the empty
		// row is needed too, as Excel grows a filter over a row right under it when the file opens
		sheet.endTable();
		sheet.row();
		sheet.row()
			.text("Total", Style.BOLD)
			.money(amount(debts.total()), Style.BOLD_MONEY)
			.money(borrowed, Style.BOLD_MONEY)
			.money(repaid, Style.BOLD_MONEY);
	}

	/** The plain words of the dashboard for each kind of entry. */
	static String typeLabel(EntryRow entry) {
		return switch (entry.type()) {
			case EXPENSE -> "Spend";
			case INCOME -> "Money added";
			case WITHDRAWAL -> "Cash withdrawn";
			case CARD_PAYMENT -> "Card bill paid";
			case ADJUSTMENT -> isOpening(entry) ? OPENING : "Balance corrected";
			case BORROW -> "Borrowed";
			case REPAY -> "Paid back";
		};
	}

	/** The description; for money with a friend the friend's name, like the transaction list. */
	static String what(EntryRow entry) {
		String friend = entry.person() == null || entry.person().isBlank() ? "a friend" : entry.person();
		return switch (entry.type()) {
			case BORROW -> "Borrowed from " + friend;
			case REPAY -> "Paid back " + friend;
			case EXPENSE -> entry.description() == null || entry.description().isBlank() ? "Spend" : entry.description();
			default -> entry.description() == null || entry.description().isBlank() ? typeLabel(entry)
					: entry.description();
		};
	}

	/** How the money moved, in the words of the transaction list. */
	static String paidBy(EntryRow entry) {
		boolean cash = entry.account() == AccountType.CASH;
		return switch (entry.type()) {
			case EXPENSE -> entry.paymentMode() == null ? "" : entry.paymentMode().label();
			case INCOME -> cash ? "To cash" : "To bank";
			case WITHDRAWAL -> "Bank to cash";
			case CARD_PAYMENT -> cash ? "From cash" : "From bank";
			case ADJUSTMENT -> switch (entry.account()) {
				case BANK -> "Bank balance";
				case CASH -> "Cash in hand";
				case CREDIT_CARD -> "Credit card due";
			};
			case BORROW, REPAY -> cash ? "Cash" : "UPI";
		};
	}

	/**
	 * Money out is negative, money in positive, as the transaction list shows it: a spend, a
	 * withdrawal, a card bill and a pay-back are out; money added and a borrow are in; a balance
	 * correction keeps its own sign.
	 */
	static BigDecimal signedAmount(EntryRow entry) {
		return switch (entry.type()) {
			case INCOME, BORROW, ADJUSTMENT -> entry.amount();
			case EXPENSE, WITHDRAWAL, CARD_PAYMENT, REPAY -> entry.amount().negate();
		};
	}

	private static boolean isOpening(EntryRow entry) {
		return OPENING.equals(entry.description());
	}

	private LocalDateTime local(Instant instant) {
		return instant == null ? null : LocalDateTime.ofInstant(instant, zone);
	}

	/** The views carry money as plain text ("1234.50"); BigDecimal reads it back without loss. */
	private static BigDecimal amount(String plain) {
		return plain == null ? null : new BigDecimal(plain);
	}

}
