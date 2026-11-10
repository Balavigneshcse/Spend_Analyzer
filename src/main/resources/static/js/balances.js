// Balance cards, the first-time step, and the small dialogs that change a balance.

import { api, ApiError } from "/js/api.js";
import { h, icon, money, todayIso, field, chipField, formNotice, openDialog, submitting, readAmount, setFieldError, toast, clear } from "/js/ui.js";
import { store, on, setBalances, dataChanged, entrySender, accountName } from "/js/store.js";

const $ = (id) => document.getElementById(id);

const BELOW_ZERO = "Below zero - correct your balance if this is wrong";

// ----- cards -----------------------------------------------------------------

/** Long amounts get a smaller size so they never spill out of the card. */
function sizeValue(node, text) {
	node.classList.toggle("long", text.length > 11 && text.length <= 14);
	node.classList.toggle("xlong", text.length > 14);
}

function paintValue(valueNode, noteNode, card, amount, note) {
	const text = money(amount);
	valueNode.textContent = text;
	sizeValue(valueNode, text);
	const below = Number(amount) < 0;
	card.classList.toggle("below", below);
	clear(noteNode);
	if (below) {
		noteNode.append(icon("alert"), h("span", null, note || BELOW_ZERO));
		noteNode.hidden = false;
	} else {
		noteNode.hidden = true;
	}
}

function paintBalances(balances) {
	paintValue($("bal-bank"), $("bal-bank-note"), $("card-bank"), balances.bank);
	paintValue($("bal-cash"), $("bal-cash-note"), $("card-cash"), balances.cash);
	paintValue($("bal-due"), $("bal-due-note"), $("card-due"), balances.creditCardDue, "Below zero - you paid more than you owed. Correct it if this is wrong");
}

/** The "Spent this month" card. totals comes from the analysis of this month (or null). */
export function paintSpent(totals) {
	const value = $("bal-spent");
	const note = $("bal-spent-note");
	clear(note);
	note.hidden = true;
	if (!totals) {
		value.textContent = "-";
		return;
	}
	const text = money(totals.spent);
	value.textContent = text;
	sizeValue(value, text);
	if (typeof totals.changePercent === "number") {
		const change = totals.changePercent;
		const size = Math.abs(change).toLocaleString("en-IN", { maximumFractionDigits: 1 }) + "%";
		if (change > 0) {
			note.append(icon("up"), h("span", null, size + " more than last month"));
		} else if (change < 0) {
			note.append(icon("down"), h("span", null, size + " less than last month"));
		} else {
			note.append(h("span", null, "Same as last month"));
		}
		note.hidden = false;
	} else if (totals.previousSpent !== null && totals.previousSpent !== undefined && Number(totals.previousSpent) > 0) {
		note.append(h("span", null, "Last month: " + money(totals.previousSpent)));
		note.hidden = false;
	}
}

// ----- first-time step -------------------------------------------------------

function setupOpening(onDone) {
	const form = $("form-opening");
	const inputs = { bank: $("open-bank"), cash: $("open-cash"), creditCardDue: $("open-card") };
	form.addEventListener("submit", (event) => {
		event.preventDefault();
		submitting(
			form,
			async () => {
				const bank = readAmount(inputs.bank, { allowZero: true });
				const cash = readAmount(inputs.cash, { allowZero: true });
				const due = readAmount(inputs.creditCardDue, { allowZero: true, optional: true });
				if (bank === null || cash === null || due === null) {
					const bad = form.querySelector('[aria-invalid="true"]');
					if (bad) {
						bad.focus();
					}
					if (bank === null && inputs.bank.value.trim() === "") {
						setFieldError(inputs.bank, "Enter your bank balance. Type 0 if it is empty.");
					}
					if (cash === null && inputs.cash.value.trim() === "") {
						setFieldError(inputs.cash, "Enter your cash. Type 0 if you have none.");
					}
					return false;
				}
				let balances;
				try {
					balances = await api.put("/api/balances/opening", { bank, cash, creditCardDue: due });
				} catch (error) {
					if (error instanceof ApiError && error.code === "already_onboarded") {
						balances = await api.get("/api/balances");
					} else {
						throw error;
					}
				}
				setBalances(balances);
				onDone();
				return true;
			},
			inputs,
		);
	});
}

// ----- dialogs ---------------------------------------------------------------

function dateField(label) {
	return field({ label: label || "Date", name: "date", type: "date", value: todayIso(), attrs: { min: "2000-01-01" } });
}

function actions(close, submitLabel) {
	return h(
		"div",
		{ class: "dialog-actions" },
		h("button", { type: "button", class: "btn secondary", on: { click: close } }, "Cancel"),
		h("button", { type: "submit", class: "btn" }, submitLabel),
	);
}

function finish(answer, close, message) {
	setBalances(answer.balances);
	dataChanged();
	close();
	toast(message);
}

function addMoney() {
	const send = entrySender();
	openDialog("Add money", (body, close) => {
		const amount = field({ label: "Amount", name: "amount", money: true });
		const account = chipField({
			label: "Add to",
			name: "account",
			value: "BANK",
			options: [
				{ value: "BANK", label: "Bank" },
				{ value: "CASH", label: "Cash" },
			],
		});
		const note = field({ label: "Note", name: "description", optional: true, attrs: { maxlength: 120, autocomplete: "off", placeholder: "pocket money, salary..." } });
		const date = dateField();
		const form = h("form", { novalidate: true }, formNotice(), amount.wrap, account.wrap, note.wrap, date.wrap, actions(close, "Add money"));
		form.addEventListener("submit", (event) => {
			event.preventDefault();
			submitting(
				form,
				async () => {
					const value = readAmount(amount.input);
					if (value === null) {
						amount.input.focus();
						return false;
					}
					const fields = { type: "INCOME", amount: value, account: account.value(), date: date.input.value || todayIso() };
					if (note.input.value.trim() !== "") {
						fields.description = note.input.value.trim();
					}
					const answer = await send(fields);
					finish(answer, close, "Money added.");
					return true;
				},
				{ amount: amount.input, description: note.input, date: date.input },
			);
		});
		body.append(form);
	});
}

function withdrawCash() {
	const send = entrySender();
	openDialog("Withdraw cash", (body, close) => {
		const amount = field({ label: "Amount", name: "amount", money: true });
		const date = dateField();
		const form = h(
			"form",
			{ novalidate: true },
			h("p", null, "Cash you took from the bank or an ATM. It moves from Bank balance to Cash in hand."),
			formNotice(),
			amount.wrap,
			date.wrap,
			actions(close, "Withdraw"),
		);
		form.addEventListener("submit", (event) => {
			event.preventDefault();
			submitting(
				form,
				async () => {
					const value = readAmount(amount.input);
					if (value === null) {
						amount.input.focus();
						return false;
					}
					const answer = await send({ type: "WITHDRAWAL", amount: value, date: date.input.value || todayIso() });
					finish(answer, close, "Cash withdrawn.");
					return true;
				},
				{ amount: amount.input, date: date.input },
			);
		});
		body.append(form);
	});
}

function payCard() {
	const send = entrySender();
	openDialog("Pay card bill", (body, close) => {
		const due = store.balances ? store.balances.creditCardDue : "0.00";
		const amount = field({ label: "Amount", name: "amount", money: true, hint: "Card due now: " + money(due) });
		const account = chipField({
			label: "Pay from",
			name: "account",
			value: "BANK",
			options: [
				{ value: "BANK", label: "Bank" },
				{ value: "CASH", label: "Cash" },
			],
		});
		const date = dateField();
		const form = h("form", { novalidate: true }, formNotice(), amount.wrap, account.wrap, date.wrap, actions(close, "Pay bill"));
		form.addEventListener("submit", (event) => {
			event.preventDefault();
			submitting(
				form,
				async () => {
					const value = readAmount(amount.input);
					if (value === null) {
						amount.input.focus();
						return false;
					}
					const answer = await send({ type: "CARD_PAYMENT", amount: value, account: account.value(), date: date.input.value || todayIso() });
					finish(answer, close, "Card bill paid.");
					return true;
				},
				{ amount: amount.input, date: date.input },
			);
		});
		body.append(form);
	});
}

function correctBalance() {
	const send = entrySender();
	openDialog("Correct a balance", (body, close) => {
		const current = () => {
			const b = store.balances || {};
			return { BANK: b.bank, CASH: b.cash, CREDIT_CARD: b.creditCardDue };
		};
		const account = chipField({
			label: "Which balance?",
			name: "account",
			value: "BANK",
			options: [
				{ value: "BANK", label: accountName("BANK") },
				{ value: "CASH", label: accountName("CASH") },
				{ value: "CREDIT_CARD", label: accountName("CREDIT_CARD") },
			],
		});
		const amount = field({ label: "Real amount now", name: "newBalance", money: true, hint: " " });
		const hint = amount.wrap.querySelector(".field-hint");
		const paintHint = () => {
			hint.textContent = "This page shows: " + money(current()[account.value()]);
		};
		for (const input of account.inputs) {
			input.addEventListener("change", paintHint);
		}
		paintHint();
		const form = h(
			"form",
			{ novalidate: true },
			h("p", null, "Use this when the amount here does not match your real balance."),
			formNotice(),
			account.wrap,
			amount.wrap,
			actions(close, "Save"),
		);
		form.addEventListener("submit", (event) => {
			event.preventDefault();
			submitting(
				form,
				async () => {
					const value = readAmount(amount.input, { allowZero: true });
					if (value === null) {
						amount.input.focus();
						return false;
					}
					const answer = await send({ type: "ADJUSTMENT", account: account.value(), newBalance: value, date: todayIso() });
					finish(answer, close, "Balance corrected.");
					return true;
				},
				{ newBalance: amount.input, amount: amount.input },
			);
		});
		body.append(form);
	});
}

// ----- start -----------------------------------------------------------------

export function setupBalances(onOnboarded) {
	for (const holder of document.querySelectorAll("[data-icon]")) {
		holder.append(icon(holder.dataset.icon));
	}
	on("balances", paintBalances);
	setupOpening(onOnboarded);
	$("act-income").addEventListener("click", addMoney);
	$("act-withdraw").addEventListener("click", withdrawCash);
	$("act-card").addEventListener("click", payCard);
	$("act-adjust").addEventListener("click", correctBalance);
}
