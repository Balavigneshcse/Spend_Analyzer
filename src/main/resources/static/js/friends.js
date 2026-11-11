// Borrowing from friends: the "Owed to friends" card, the "Friends you owe" list,
// and the two dialogs "Borrow money" and "Pay back a friend".
// A friend's name is whatever the user typed: it only ever goes into the page as text.

import { api, ApiError, errorText } from "/js/api.js";
import { h, money, moneyShort, showDate, todayIso, field, selectField, chipField, formNotice, openDialog, submitting, readAmount, setFieldError, toast } from "/js/ui.js";
import { store, on, setBalances, setDebts, dataChanged, entrySender } from "/js/store.js";
import { sizeValue, dateField, actions } from "/js/balances.js";

const $ = (id) => document.getElementById(id);

const SHOWN_AT_FIRST = 4;

const state = { loadNo: 0, pending: null, error: null, showAll: false, opening: false };

// ----- what is owed ----------------------------------------------------------

/** Reads /api/debts again. Never rejects: a failure is kept in state.error. */
function loadDebts() {
	state.loadNo += 1;
	const mine = state.loadNo;
	const pending = api
		.get("/api/debts")
		.then(
			(debts) => {
				if (mine === state.loadNo) {
					state.error = null;
					setDebts(debts);
				}
			},
			(error) => {
				if (mine === state.loadNo) {
					state.error = error;
				}
			},
		)
		.then(() => {
			if (state.pending === pending) {
				state.pending = null;
			}
		});
	state.pending = pending;
	return pending;
}

/** The newest answer of /api/debts, waiting for a reload that is on its way. Null when it cannot be read. */
async function freshDebts() {
	if (!state.pending && (store.debts === null || state.error)) {
		loadDebts();
	}
	while (state.pending) {
		await state.pending;
	}
	return state.error ? null : store.debts;
}

function people() {
	return store.debts && Array.isArray(store.debts.people) ? store.debts.people : [];
}

function knownNames() {
	return store.debts && Array.isArray(store.debts.knownNames) ? store.debts.knownNames : [];
}

/** "₹1,500" for whole rupees, "₹1,500.50" otherwise. */
function tidyMoney(value) {
	return Number.isInteger(Number(value)) ? moneyShort(value) : money(value);
}

// ----- the card --------------------------------------------------------------

function paintCard() {
	const balances = store.balances;
	if (!balances) {
		return;
	}
	const owed = balances.owedToFriends || "0.00";
	const value = $("bal-owed");
	const text = money(owed);
	value.textContent = text;
	sizeValue(value, text);
	const note = $("bal-owed-note");
	const count = people().length;
	if (!(Number(owed) > 0)) {
		note.textContent = "You owe nobody";
		note.hidden = false;
	} else if (count > 0) {
		note.textContent = count === 1 ? "1 friend" : count + " friends";
		note.hidden = false;
	} else {
		note.hidden = true; // the list of friends has not arrived yet
	}
}

// ----- the "Friends you owe" list --------------------------------------------

/** The rows now in the list, by friend. A row is kept and only its text changes, so a focused button stays. */
let rows = new Map();

function makeRow(name) {
	const sub = h("span", { class: "friend-sub" });
	const owed = h("span", { class: "friend-owed num" });
	const button = h("button", { type: "button", class: "btn secondary friend-pay", on: { click: () => payBack(name) } }, "Pay back", h("span", { class: "sr-only" }, " " + name));
	const li = h("li", { class: "friend" }, h("div", { class: "friend-who" }, h("span", { class: "friend-name" }, name), sub), owed, button);
	return { li, sub, owed };
}

function fillRow(row, person) {
	const parts = ["Borrowed " + tidyMoney(person.borrowed)];
	if (Number(person.repaid) > 0) {
		parts.push("paid back " + tidyMoney(person.repaid));
	}
	if (person.lastDate) {
		parts.push("last " + showDate(person.lastDate));
	}
	// each part stays in one piece (no-break spaces): on a narrow screen the line breaks only after a dot
	row.sub.textContent = parts.map((part) => part.replace(/ /g, " ")).join(" · ");
	row.owed.textContent = money(person.owed);
}

function paintList() {
	const section = $("owe");
	const list = $("owe-list");
	const more = $("owe-more");
	const all = people();
	const active = document.activeElement;
	const hadFocus = section.contains(active);

	const few = all.length <= SHOWN_AT_FIRST + 1;
	const shown = few || state.showAll ? all : all.slice(0, SHOWN_AT_FIRST);
	const next = new Map();
	shown.forEach((person, index) => {
		const row = rows.get(person.name) || makeRow(person.name);
		fillRow(row, person);
		next.set(person.name, row);
		if (list.children[index] !== row.li) {
			list.insertBefore(row.li, list.children[index] || null);
		}
	});
	while (list.children.length > shown.length) {
		list.lastElementChild.remove();
	}
	rows = next;
	more.hidden = few;
	more.textContent = state.showAll ? "Show fewer" : "Show all " + all.length + " friends";
	more.setAttribute("aria-expanded", String(state.showAll));
	section.hidden = all.length === 0;

	// the focused row went away (paid back in full) or was moved: do not drop the focus on the floor
	if (hadFocus && document.activeElement !== active) {
		const target = list.contains(active) || (active === more && !more.hidden) ? active : $("act-repay");
		target.focus({ preventScroll: true });
	}
}

// ----- dialogs ---------------------------------------------------------------

const cleanName = (text) => String(text || "").replace(/\s+/g, " ").trim();

/** The UPI / Cash choice. UPI moves the bank balance, cash moves cash in hand. */
function howField(label) {
	return chipField({
		label,
		name: "account",
		value: "BANK",
		options: [
			{ value: "BANK", label: "UPI" },
			{ value: "CASH", label: "Cash" },
		],
	});
}

let chipNo = 0;

/** Lets a message from the server show under the chips, as it does under any other field. */
function chipInput(chips) {
	chipNo += 1;
	const first = chips.inputs[0];
	first.id = "how" + chipNo;
	chips.error.id = first.id + "-err";
	return first;
}

function noteField(placeholder) {
	return field({ label: "Note", name: "description", optional: true, attrs: { maxlength: 120, autocomplete: "off", placeholder } });
}

/** A dialog that was opened from a row which is gone when it closes: the focus goes to the action button. */
function focusFallback() {
	window.setTimeout(() => {
		if (!document.activeElement || document.activeElement === document.body) {
			$("act-repay").focus({ preventScroll: true });
		}
	}, 0);
}

function finish(answer, close, message) {
	setBalances(answer.balances);
	dataChanged(); // the list of friends, the transactions and the analysis load again
	close();
	toast(message);
}

function tell(title, ...lines) {
	openDialog(
		title,
		(body, close) => {
			for (const line of lines) {
				body.append(h("p", null, line));
			}
			body.append(h("div", { class: "dialog-actions" }, h("button", { type: "button", class: "btn", "data-autofocus": true, on: { click: close } }, "Close")));
		},
		{ typeNow: false },
	);
}

function borrowMoney() {
	const send = entrySender();
	openDialog("Borrow money", (body, close) => {
		const listId = "friend-names-" + Date.now();
		const person = field({ label: "Friend's name", name: "person", attrs: { maxlength: 40, autocomplete: "off", autocapitalize: "words", spellcheck: "false", list: listId } });
		const names = h("datalist", { id: listId });
		for (const name of knownNames()) {
			names.append(h("option", { value: name }));
		}
		person.wrap.append(names);
		const amount = field({ label: "Amount", name: "amount", money: true });
		const date = dateField();
		const account = howField("Received by");
		const accountInput = chipInput(account);
		const note = noteField("for rent, a trip, books...");
		const form = h(
			"form",
			{ novalidate: true },
			h("p", null, "This adds to your bank balance (or cash) and you will owe your friend."),
			formNotice(),
			person.wrap,
			h("div", { class: "row-2" }, amount.wrap, date.wrap),
			account.wrap,
			note.wrap,
			actions(close, "Borrow"),
		);
		form.addEventListener("submit", (event) => {
			event.preventDefault();
			submitting(
				form,
				async () => {
					const name = cleanName(person.input.value);
					const value = readAmount(amount.input);
					if (name === "") {
						setFieldError(person.input, "Type your friend's name.");
					}
					if (name === "" || value === null) {
						(name === "" ? person.input : amount.input).focus();
						return false;
					}
					const fields = { type: "BORROW", person: name, amount: value, account: account.value(), date: date.input.value || todayIso() };
					if (note.input.value.trim() !== "") {
						fields.description = note.input.value.trim();
					}
					const answer = await send(fields);
					finish(answer, close, "Borrowed money added.");
					return true;
				},
				{ person: person.input, amount: amount.input, account: accountInput, date: date.input, description: note.input },
			);
		});
		body.append(form);
	});
}

/** Opens "Pay back a friend". name: the friend to start with (from a row of the list), or null. */
async function payBack(name) {
	if (state.opening) {
		return;
	}
	state.opening = true;
	let debts;
	try {
		debts = await freshDebts();
	} finally {
		state.opening = false;
	}
	if (!debts) {
		tell("Pay back a friend", "Cannot load what you owe. " + errorText(state.error));
		return;
	}
	const owing = Array.isArray(debts.people) ? debts.people : [];
	if (owing.length === 0) {
		tell("Pay back a friend", "You do not owe anyone right now.", "When you borrow from a friend, add it with Borrow money.");
		return;
	}
	const send = entrySender();
	openDialog("Pay back a friend", (body, close) => {
		const start = Math.max(
			0,
			owing.findIndex((p) => p.name === name),
		);
		const friend = selectField({
			label: "Friend",
			name: "person",
			value: String(start),
			options: owing.map((p, i) => ({ value: String(i), label: p.name + " - you owe " + money(p.owed) })),
		});
		const chosen = () => owing[Number(friend.input.value)] || owing[0];
		const amount = field({ label: "Amount", name: "amount", money: true, value: chosen().owed });
		const date = dateField();
		const owes = h("span", { id: amount.input.id + "-owes" });
		amount.input.setAttribute("aria-describedby", amount.input.getAttribute("aria-describedby") + " " + owes.id);
		const fill = () => {
			amount.input.value = chosen().owed;
			setFieldError(amount.input, "");
		};
		const paintOwes = () => {
			owes.textContent = "You owe this friend " + money(chosen().owed) + ".";
		};
		paintOwes();
		friend.input.addEventListener("change", () => {
			paintOwes();
			fill();
		});
		const account = howField("Paid by");
		const accountInput = chipInput(account);
		const note = noteField();
		const form = h(
			"form",
			{ novalidate: true },
			formNotice(),
			friend.wrap,
			h("div", { class: "row-2" }, amount.wrap, date.wrap),
			h("p", { class: "hint-row" }, owes, h("button", { type: "button", class: "btn link", on: { click: fill } }, "Pay all")),
			account.wrap,
			note.wrap,
			actions(close, "Pay back"),
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
					const fields = { type: "REPAY", person: chosen().name, amount: value, account: account.value(), date: date.input.value || todayIso() };
					if (note.input.value.trim() !== "") {
						fields.description = note.input.value.trim();
					}
					let answer;
					try {
						answer = await send(fields);
					} catch (error) {
						if (error instanceof ApiError && error.status === 400) {
							loadDebts(); // what this page knows may be old (another tab or phone)
						}
						throw error;
					}
					finish(answer, close, "Paid back.");
					return true;
				},
				{ person: friend.input, amount: amount.input, account: accountInput, date: date.input, description: note.input },
			);
		});
		body.append(form);
	}, { onClose: focusFallback });
}

// ----- start -----------------------------------------------------------------

export function setupFriends() {
	on("balances", paintCard);
	on("debts", () => {
		paintCard();
		paintList();
	});
	on("changed", loadDebts);
	$("act-borrow").addEventListener("click", borrowMoney);
	$("act-repay").addEventListener("click", () => payBack(null));
	$("owe-more").addEventListener("click", () => {
		state.showAll = !state.showAll;
		paintList();
	});
	paintCard();
	return loadDebts();
}
