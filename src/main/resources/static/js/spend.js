// "Add a spend": the form the user fills right after paying. Fast, with a live AI category line.

import { api } from "/js/api.js";
import { h, icon, clear, todayIso, showDate, submitting, readAmount, setFieldError, toast } from "/js/ui.js";
import { store, emit, setBalances, dataChanged, entrySender, remember, recall } from "/js/store.js";

const $ = (id) => document.getElementById(id);

const PREVIEW_DELAY_MS = 350;

export function setupSpend() {
	const form = $("form-spend");
	const what = $("spend-what");
	const amount = $("spend-amount");
	const date = $("spend-date");
	const line = $("ai-line");
	const pickBox = $("ai-pick");
	const pick = $("spend-category");
	const modes = Array.from(form.querySelectorAll('input[name="mode"]'));
	const send = entrySender();

	// what the AI said for the text now in the box, and what the user chose (if anything)
	let guess = null;
	let chosen = null;
	let previewNo = 0;
	let previewTimer = null;
	let previewAbort = null;

	// ----- payment mode, remembered -------------------------------------------
	const lastMode = recall("mode");
	if (modes.some((m) => m.value === lastMode)) {
		for (const m of modes) {
			m.checked = m.value === lastMode;
		}
	}
	const mode = () => (modes.find((m) => m.checked) || modes[0]).value;
	for (const m of modes) {
		m.addEventListener("change", () => remember("mode", mode()));
	}

	// ----- date -----------------------------------------------------------------
	let dateTouched = false;
	const maxDate = () => {
		const tomorrow = new Date();
		tomorrow.setDate(tomorrow.getDate() + 1);
		return tomorrow.getFullYear() + "-" + String(tomorrow.getMonth() + 1).padStart(2, "0") + "-" + String(tomorrow.getDate()).padStart(2, "0");
	};
	date.value = todayIso();
	date.max = maxDate();
	const dateNote = $("spend-date-note");
	const paintDateNote = () => {
		clear(dateNote);
		dateNote.hidden = !dateTouched;
		if (dateTouched) {
			dateNote.append(
				h("span", null, "Date is " + showDate(date.value, true) + ", not today."),
				h(
					"button",
					{
						type: "button",
						class: "ai-change",
						on: {
							click: () => {
								date.value = todayIso();
								dateTouched = false;
								setFieldError(date, "");
								paintDateNote();
							},
						},
					},
					"Use today",
				),
			);
		}
	};
	date.addEventListener("change", () => {
		dateTouched = date.value !== "" && date.value !== todayIso();
		paintDateNote();
	});
	// a page left open over midnight should not keep yesterday as "today"
	document.addEventListener("visibilitychange", () => {
		if (!document.hidden && !dateTouched) {
			date.value = todayIso();
			date.max = maxDate();
		}
	});

	// ----- category list --------------------------------------------------------
	function fillPick() {
		clear(pick);
		pick.append(h("option", { value: "" }, "Pick a category"));
		for (const category of store.meta.categories) {
			pick.append(h("option", { value: category.code }, category.label));
		}
	}
	fillPick();

	function paintLine() {
		clear(line);
		const text = h("span", { class: "ai-text" });
		if (chosen) {
			const label = (store.meta.categories.find((c) => c.code === chosen) || {}).label || chosen;
			text.append(h("strong", null, label), " - your choice");
			line.append(icon("check"), text, h("button", { type: "button", class: "ai-change", on: { click: undoChoice } }, "Undo"));
			return;
		}
		if (what.value.trim() === "") {
			text.append("The category is picked for you.");
			line.append(text);
			pickBox.hidden = true;
			return;
		}
		if (!guess) {
			text.append("Finding the category...");
			line.append(text);
			return;
		}
		if (guess.needsReview) {
			text.append(h("strong", null, "Not sure - please pick a category"));
			line.append(icon("alert"), text);
			pickBox.hidden = false;
			return;
		}
		text.append(h("strong", null, guess.categoryLabel));
		if (guess.explanation) {
			text.append(" - ", guess.explanation);
		}
		line.append(icon("spark"), text, h("button", { type: "button", class: "ai-change", "aria-expanded": String(!pickBox.hidden), "aria-controls": "ai-pick", on: { click: toggleChange } }, "Change"));
	}

	function toggleChange() {
		pickBox.hidden = !pickBox.hidden;
		paintLine();
		if (!pickBox.hidden) {
			pick.value = "";
			pick.focus();
		}
	}

	function undoChoice() {
		chosen = null;
		pick.value = "";
		pickBox.hidden = !(guess && guess.needsReview);
		paintLine();
		what.focus();
	}

	pick.addEventListener("change", () => {
		chosen = pick.value || null;
		if (chosen) {
			pickBox.hidden = true;
		}
		paintLine();
	});

	// ----- live preview ---------------------------------------------------------
	function askPreview() {
		const text = what.value.trim();
		previewNo += 1;
		const mine = previewNo;
		if (previewAbort) {
			previewAbort.abort();
			previewAbort = null;
		}
		if (text === "") {
			guess = null;
			paintLine();
			return;
		}
		const body = { description: text, paymentMode: mode() };
		previewAbort = new AbortController();
		api.post("/api/entries/preview", body, { signal: previewAbort.signal })
			.then((answer) => {
				if (mine !== previewNo) {
					return; // an older answer arrived late
				}
				guess = answer;
				if (!chosen) {
					pickBox.hidden = !answer.needsReview;
				}
				paintLine();
			})
			.catch(() => {
				if (mine === previewNo && !chosen) {
					guess = null;
					clear(line);
					line.append(h("span", { class: "ai-text" }, "The category is picked when you save."));
				}
			});
	}

	what.addEventListener("input", () => {
		setFieldError(what, "");
		window.clearTimeout(previewTimer);
		previewNo += 1; // anything still on its way is now out of date
		if (what.value.trim() === "") {
			guess = null;
			if (!chosen) {
				pickBox.hidden = true;
			}
			paintLine();
			return;
		}
		previewTimer = window.setTimeout(askPreview, PREVIEW_DELAY_MS);
	});

	what.addEventListener("keydown", (event) => {
		if (event.key === "Enter" && amount.value.trim() === "") {
			event.preventDefault();
			amount.focus();
		}
	});

	amount.addEventListener("input", () => setFieldError(amount, ""));

	// ----- save -----------------------------------------------------------------
	form.addEventListener("submit", (event) => {
		event.preventDefault();
		submitting(
			form,
			async () => {
				const description = what.value.trim();
				let ok = true;
				if (description === "") {
					setFieldError(what, "Write what you bought.");
					ok = false;
				}
				const value = readAmount(amount);
				if (value === null) {
					ok = false;
				}
				if (!ok) {
					(description === "" ? what : amount).focus();
					return false;
				}
				const fields = { type: "EXPENSE", description, amount: value, paymentMode: mode(), date: date.value || todayIso() };
				if (chosen) {
					fields.category = chosen; // only when the user really picked one
				}
				const answer = await send(fields);
				remember("mode", fields.paymentMode);
				setBalances(answer.balances);
				emit("entry-added", answer.entry);
				dataChanged();

				// ready for the next spend: keep the payment mode (and a date the user set)
				window.clearTimeout(previewTimer);
				previewNo += 1;
				what.value = "";
				amount.value = "";
				guess = null;
				chosen = null;
				pick.value = "";
				pickBox.hidden = true;
				paintLine();
				if (!dateTouched) {
					date.value = todayIso();
				}
				const entry = answer.entry;
				let message = "Saved: " + entry.description + " - " + (entry.categoryLabel || "no category");
				if (entry.needsReview) {
					message += ". Please check the category";
				}
				if (dateTouched) {
					message += " (" + showDate(entry.date) + ")";
				}
				toast(message);
				window.setTimeout(() => what.focus(), 0);
				return true;
			},
			{ description: what, amount, date, category: pick, paymentMode: modes[0] },
		);
	});

	paintLine();
}
