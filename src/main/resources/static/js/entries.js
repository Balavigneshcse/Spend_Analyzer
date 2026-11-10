// Transactions: the statement-style list, its filters, and the dialog to edit or delete one entry.

import { api, ApiError, errorText } from "/js/api.js";
import { h, clear, money, showDate, monthName, isoDate, field, selectField, chipField, formNotice, openDialog, submitting, readAmount, setFieldError, toast, announce } from "/js/ui.js";
import { store, on, setBalances, dataChanged, modeLabel, accountName } from "/js/store.js";

const $ = (id) => document.getElementById(id);

const PAGE_SIZE = 20;
const MAX_RELOAD_PAGES = 5;

const state = {
	items: [],
	pages: 0,
	total: 0,
	hasMore: false,
	loadNo: 0,
	abort: null,
};

// ----- how one entry reads ---------------------------------------------------

/** Plain words for an entry: { what, note, how, amount } */
export function describe(entry) {
	const amount = money(entry.amount);
	switch (entry.type) {
		case "EXPENSE":
			return { what: entry.description || "Spend", how: modeLabel(entry.paymentMode), amount };
		case "INCOME":
			return { what: "Money added", note: entry.description, how: entry.account === "CASH" ? "To cash" : "To bank", amount: "+" + amount };
		case "WITHDRAWAL":
			return { what: "Cash withdrawn", note: entry.description, how: "Bank to cash", amount };
		case "CARD_PAYMENT":
			return { what: "Card bill paid", note: entry.description, how: entry.account === "CASH" ? "From cash" : "From bank", amount };
		case "ADJUSTMENT": {
			const opening = entry.description === "Opening balance";
			const signed = Number(entry.amount) > 0 ? "+" + amount : amount;
			return { what: opening ? "Opening balance" : "Balance corrected", note: opening ? null : entry.description, how: accountName(entry.account), amount: signed };
		}
		default:
			return { what: entry.description || entry.type, how: "", amount };
	}
}

function row(entry) {
	const words = describe(entry);
	const title = words.note ? words.what + " - " + words.note : words.what;
	const button = h(
		"button",
		{ type: "button", class: "tx-row", "data-id": entry.id },
		h("span", { class: "tx-desc" }, h("span", { class: "tx-what" }, title), entry.needsReview ? h("span", { class: "tag" }, "Check category") : null),
		h("span", { class: "tx-amt num" }, words.amount),
		h(
			"span",
			{ class: "tx-sub" },
			h("span", { class: "tx-date" }, showDate(entry.date)),
			h("span", { class: "tx-cat" }, entry.type === "EXPENSE" ? entry.categoryLabel || "" : ""),
			h("span", { class: "tx-mode" }, words.how),
		),
	);
	button.addEventListener("click", () => openEntry(entry));
	return h("li", null, button);
}

// ----- filters ---------------------------------------------------------------

function monthRange(year, monthIndex) {
	return { from: isoDate(new Date(year, monthIndex, 1)), to: isoDate(new Date(year, monthIndex + 1, 0)) };
}

function fillFilters() {
	const period = $("f-period");
	clear(period);
	period.append(h("option", { value: "" }, "All dates"));
	const now = new Date();
	for (let i = 0; i < 12; i += 1) {
		const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
		const label = i === 0 ? "This month" : i === 1 ? "Last month" : monthName(d.getFullYear(), d.getMonth());
		period.append(h("option", { value: d.getFullYear() + "-" + d.getMonth() }, label));
	}
	period.append(h("option", { value: "custom" }, "Pick dates..."));

	const mode = $("f-mode");
	clear(mode);
	mode.append(h("option", { value: "" }, "Paid by: all"));
	for (const m of store.meta.paymentModes) {
		mode.append(h("option", { value: m.code }, m.label));
	}

	const category = $("f-category");
	clear(category);
	category.append(h("option", { value: "" }, "All categories"));
	for (const c of store.meta.categories) {
		category.append(h("option", { value: c.code }, c.label));
	}
}

function readFilters() {
	const query = {};
	const period = $("f-period").value;
	if (period === "custom") {
		if ($("f-from").value) {
			query.from = $("f-from").value;
		}
		if ($("f-to").value) {
			query.to = $("f-to").value;
		}
	} else if (period) {
		const [year, month] = period.split("-").map(Number);
		Object.assign(query, monthRange(year, month));
	}
	if ($("f-mode").value) {
		query.mode = $("f-mode").value;
	}
	if ($("f-category").value) {
		query.category = $("f-category").value;
	}
	if ($("f-review").checked) {
		query.needsReview = "true";
	}
	const q = $("f-q").value.trim();
	if (q) {
		query.q = q;
	}
	return query;
}

function filterCount() {
	const query = readFilters();
	delete query.q;
	let count = Object.keys(query).length;
	if (query.from && query.to) {
		count -= 1;
	}
	return count;
}

function paintFilterState() {
	const count = filterCount();
	const toggle = $("f-toggle");
	clear(toggle);
	toggle.append("Filters");
	if (count > 0) {
		toggle.append(h("span", { class: "count" }, String(count)), h("span", { class: "sr-only" }, " in use"));
	}
	$("f-clear").hidden = count === 0 && $("f-q").value.trim() === "";
	$("f-range").hidden = $("f-period").value !== "custom";
}

function anyFilter() {
	return Object.keys(readFilters()).length > 0;
}

// ----- loading ---------------------------------------------------------------

function path(page) {
	const params = new URLSearchParams(readFilters());
	params.set("page", String(page));
	params.set("size", String(PAGE_SIZE));
	return "/api/entries?" + params.toString();
}

function paint() {
	const list = $("tx-list");
	clear(list);
	for (const entry of state.items) {
		list.append(row(entry));
	}
	const empty = $("tx-empty");
	if (state.items.length === 0) {
		empty.textContent = anyFilter() ? "Nothing matches these filters." : "No transactions yet. Add your first spend.";
		empty.hidden = false;
	} else {
		empty.hidden = true;
	}
	$("tx-more").hidden = !state.hasMore;
	$("tx-count").textContent = state.total === 0 ? "" : state.items.length < state.total ? state.items.length + " of " + state.total : String(state.total);
	$("tx-count").setAttribute("aria-label", state.total + " transactions");
}

/** Loads pages 0..pages-1 again (after a change or a new filter). */
async function load(pages) {
	state.loadNo += 1;
	const mine = state.loadNo;
	if (state.abort) {
		state.abort.abort();
	}
	state.abort = new AbortController();
	const signal = state.abort.signal;
	const list = $("tx-list");
	const error = $("f-error");
	list.classList.add("loading");
	try {
		const items = [];
		let last = null;
		for (let page = 0; page < pages; page += 1) {
			last = await api.get(path(page), { signal });
			if (mine !== state.loadNo) {
				return;
			}
			items.push(...last.items);
			if (!last.hasMore) {
				break;
			}
		}
		state.items = items;
		state.pages = Math.max(1, Math.ceil(items.length / PAGE_SIZE));
		state.total = last ? last.total : 0;
		state.hasMore = last ? last.hasMore : false;
		error.hidden = true;
		paint();
	} catch (problem) {
		if (problem && problem.name === "AbortError") {
			return;
		}
		if (mine !== state.loadNo) {
			return;
		}
		error.textContent = problem instanceof ApiError && problem.fields ? Object.values(problem.fields)[0] : errorText(problem);
		error.hidden = false;
	} finally {
		if (mine === state.loadNo) {
			list.classList.remove("loading");
		}
	}
}

async function showMore() {
	const button = $("tx-more");
	button.disabled = true;
	button.classList.add("busy");
	state.loadNo += 1;
	const mine = state.loadNo;
	try {
		const answer = await api.get(path(state.pages));
		if (mine !== state.loadNo) {
			return;
		}
		const known = new Set(state.items.map((e) => e.id));
		const fresh = answer.items.filter((e) => !known.has(e.id));
		state.items.push(...fresh);
		state.pages += 1;
		state.total = answer.total;
		state.hasMore = answer.hasMore;
		const firstNew = fresh[0];
		paint();
		if (firstNew) {
			const target = $("tx-list").querySelector('[data-id="' + firstNew.id + '"]');
			if (target) {
				target.focus({ preventScroll: true });
			}
		}
		announce(fresh.length + " more shown.");
	} catch (problem) {
		$("f-error").textContent = errorText(problem);
		$("f-error").hidden = false;
	} finally {
		button.disabled = false;
		button.classList.remove("busy");
	}
}

function reload() {
	return load(Math.min(Math.max(state.pages, 1), MAX_RELOAD_PAGES));
}

function sortItems() {
	state.items.sort((a, b) => (a.date === b.date ? (a.createdAt < b.createdAt ? 1 : -1) : a.date < b.date ? 1 : -1));
}

// ----- the entry dialog ------------------------------------------------------

function afterChange(balances, message, close) {
	setBalances(balances);
	dataChanged();
	close();
	toast(message);
}

function confirmDelete(entry, body, close, back) {
	const words = describe(entry);
	clear(body);
	const notice = formNotice();
	const form = h(
		"form",
		{ novalidate: true },
		notice,
		h("p", { class: "confirm-text" }, "Delete this entry?"),
		h("p", { class: "confirm-what" }, words.what + (words.note ? " - " + words.note : "") + " - " + words.amount + " - " + showDate(entry.date, true)),
		h("p", { class: "confirm-text" }, "Your balance will be put back, as if this entry was never made. This cannot be undone."),
		h(
			"div",
			{ class: "dialog-actions" },
			h(
				"button",
				{
					type: "button",
					class: "btn secondary",
					"data-autofocus": true,
					on: {
						click: () => {
							back();
							const again = body.querySelector("[data-delete]");
							if (again) {
								again.focus();
							}
						},
					},
				},
				"Keep it",
			),
			h("button", { type: "submit", class: "btn strong" }, "Delete"),
		),
	);
	form.addEventListener("submit", (event) => {
		event.preventDefault();
		submitting(form, async () => {
			let answer;
			try {
				answer = await api.del("/api/entries/" + encodeURIComponent(entry.id));
			} catch (error) {
				if (error instanceof ApiError && error.status === 404) {
					// already gone (deleted in another tab): just refresh
					answer = { balances: await api.get("/api/balances") };
				} else {
					throw error;
				}
			}
			state.items = state.items.filter((e) => e.id !== entry.id);
			state.total = Math.max(0, state.total - 1);
			paint();
			afterChange(answer.balances, "Deleted. Balance put back.", close);
			return true;
		});
	});
	body.append(form);
	const keep = form.querySelector("[data-autofocus]");
	keep.focus();
}

function editExpense(entry, body, close) {
	clear(body);
	const what = field({ label: "What did you buy?", name: "description", value: entry.description, attrs: { maxlength: 120, autocomplete: "off", autocapitalize: "none", spellcheck: "false" } });
	const amount = field({ label: "Amount", name: "amount", money: true, value: entry.amount });
	const date = field({ label: "Date", name: "date", type: "date", value: entry.date, attrs: { min: "2000-01-01" } });
	const mode = chipField({
		label: "Paid by",
		name: "mode",
		value: entry.paymentMode,
		options: store.meta.paymentModes.map((m) => ({ value: m.code, label: m.label })),
	});
	const category = selectField({
		label: "Category",
		name: "category",
		value: entry.category,
		options: store.meta.categories.map((c) => ({ value: c.code, label: c.label })),
	});
	const hint = h("p", { class: "field-hint" }, entry.needsReview ? "We were not sure about this one. Pick the right category." : entry.userCorrected ? "You picked this category." : "Picked for you. Change it if it is wrong.");
	category.wrap.insertBefore(hint, category.wrap.querySelector(".field-error"));

	const form = h(
		"form",
		{ novalidate: true },
		formNotice(),
		what.wrap,
		h("div", { class: "row-2" }, amount.wrap, date.wrap),
		mode.wrap,
		category.wrap,
		h(
			"div",
			{ class: "dialog-actions" },
			h("button", { type: "button", class: "btn secondary", "data-delete": true, on: { click: () => confirmDelete(entry, body, close, () => editExpense(entry, body, close)) } }, "Delete"),
			h("span", { class: "spacer" }),
			h("button", { type: "submit", class: "btn" }, "Save"),
		),
	);
	form.addEventListener("submit", (event) => {
		event.preventDefault();
		submitting(
			form,
			async () => {
				const description = what.input.value.trim();
				if (description === "") {
					setFieldError(what.input, "Write what you bought.");
					what.input.focus();
					return false;
				}
				const value = readAmount(amount.input);
				if (value === null) {
					amount.input.focus();
					return false;
				}
				const change = {};
				if (description !== entry.description) {
					change.description = description;
				}
				if (Number(value) !== Number(entry.amount)) {
					change.amount = value;
				}
				if (mode.value() !== entry.paymentMode) {
					change.paymentMode = mode.value();
				}
				if (date.input.value && date.input.value !== entry.date) {
					change.date = date.input.value;
				}
				if (category.input.value !== entry.category) {
					change.category = category.input.value; // only when the user really picked another one
				}
				if (Object.keys(change).length === 0) {
					close();
					return true;
				}
				const answer = await api.patch("/api/entries/" + encodeURIComponent(entry.id), change);
				const index = state.items.findIndex((e) => e.id === entry.id);
				if (index >= 0) {
					state.items[index] = answer.entry;
					sortItems();
					paint();
				}
				afterChange(answer.balances, "Saved.", close);
				return true;
			},
			{ description: what.input, amount: amount.input, date: date.input, category: category.input },
		);
	});
	body.append(form);
}

function showOther(entry, body, close) {
	clear(body);
	const words = describe(entry);
	const facts = h("dl", { class: "facts" });
	const add = (name, value) => {
		if (value) {
			facts.append(h("dt", null, name), h("dd", null, value));
		}
	};
	add("Amount", words.amount);
	add("Balance", entry.type === "WITHDRAWAL" ? "Bank balance to Cash in hand" : entry.type === "CARD_PAYMENT" ? (entry.account === "CASH" ? "Cash in hand" : "Bank balance") + " and Credit card due" : accountName(entry.account));
	add("Date", showDate(entry.date, true));
	add("Note", words.note);
	body.append(
		facts,
		h("p", null, "This entry cannot be changed. If it is wrong, delete it and add it again."),
		h(
			"div",
			{ class: "dialog-actions" },
			h("button", { type: "button", class: "btn secondary", "data-delete": true, on: { click: () => confirmDelete(entry, body, close, () => showOther(entry, body, close)) } }, "Delete"),
			h("span", { class: "spacer" }),
			h("button", { type: "button", class: "btn", "data-autofocus": true, on: { click: close } }, "Close"),
		),
	);
}

function openEntry(entry) {
	const words = describe(entry);
	openDialog(
		entry.type === "EXPENSE" ? "Edit spend" : words.what,
		(body, close) => {
			if (entry.type === "EXPENSE") {
				editExpense(entry, body, close);
			} else {
				showOther(entry, body, close);
			}
		},
		{ typeNow: false },
	);
}

// ----- start -----------------------------------------------------------------

/** Used by the analysis note "Show them": list only the spends that need a category check. */
export function showNeedsChecking() {
	$("f-review").checked = true;
	$("f-more").classList.add("open");
	$("f-toggle").setAttribute("aria-expanded", "true");
	paintFilterState();
	load(1);
	const heading = $("h-tx");
	heading.setAttribute("tabindex", "-1");
	heading.focus({ preventScroll: true });
	$("tx").scrollIntoView({ block: "start", behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
}

export function setupEntries() {
	fillFilters();
	paintFilterState();

	let searchTimer = null;
	const changed = () => {
		paintFilterState();
		load(1);
	};
	$("f-q").addEventListener("input", () => {
		window.clearTimeout(searchTimer);
		searchTimer = window.setTimeout(changed, 350);
	});
	for (const id of ["f-period", "f-mode", "f-category", "f-review", "f-from", "f-to"]) {
		$(id).addEventListener("change", changed);
	}
	$("filters").addEventListener("submit", (event) => {
		event.preventDefault();
		window.clearTimeout(searchTimer);
		changed();
	});
	$("f-toggle").addEventListener("click", () => {
		const open = $("f-more").classList.toggle("open");
		$("f-toggle").setAttribute("aria-expanded", String(open));
	});
	$("f-clear").addEventListener("click", () => {
		$("f-q").value = "";
		$("f-period").value = "";
		$("f-mode").value = "";
		$("f-category").value = "";
		$("f-review").checked = false;
		$("f-from").value = "";
		$("f-to").value = "";
		changed();
		$("f-q").focus();
	});
	$("tx-more").addEventListener("click", showMore);

	// a new spend shows at once (when no filter hides it); the full reload follows
	on("entry-added", (entry) => {
		if (!anyFilter() && !state.items.some((e) => e.id === entry.id)) {
			state.items.unshift(entry);
			sortItems();
			state.total += 1;
			paint();
		}
	});
	on("changed", reload);

	return load(1);
}
