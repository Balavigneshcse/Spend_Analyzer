// Small DOM, format and form helpers shared by both pages.
// Text from the server or the user only ever goes in as text nodes.

import { ApiError, errorText } from "/js/api.js";

const SVG_NS = "http://www.w3.org/2000/svg";

// ----- elements -----------------------------------------------------------

function setAttrs(node, attrs) {
	for (const [key, value] of Object.entries(attrs || {})) {
		if (value === null || value === undefined || value === false) {
			continue;
		}
		if (key === "class") {
			node.setAttribute("class", value);
		} else if (key === "text") {
			node.textContent = value;
		} else if (key === "on") {
			for (const [event, handler] of Object.entries(value)) {
				node.addEventListener(event, handler);
			}
		} else if (/^on/i.test(key)) {
			throw new Error("event attributes are not allowed");
		} else {
			node.setAttribute(key, value === true ? "" : String(value));
		}
	}
}

function append(node, children) {
	for (const child of children.flat(Infinity)) {
		if (child === null || child === undefined || child === false) {
			continue;
		}
		node.append(child instanceof Node ? child : document.createTextNode(String(child)));
	}
	return node;
}

/** h("p", { class: "x" }, "text", otherNode) */
export function h(tag, attrs, ...children) {
	const node = document.createElement(tag);
	setAttrs(node, attrs);
	return append(node, children);
}

export function s(tag, attrs, ...children) {
	const node = document.createElementNS(SVG_NS, tag);
	setAttrs(node, attrs);
	return append(node, children);
}

export function clear(node) {
	node.replaceChildren();
	return node;
}

const ICONS = {
	close: ["M6 6l12 12", "M18 6L6 18"],
	chevronDown: ["M6 9l6 6 6-6"],
	chevronRight: ["M9 6l6 6-6 6"],
	check: ["M5 12.5l4.5 4.5L19 7.5"],
	plus: ["M12 5v14", "M5 12h14"],
	alert: ["M12 4l9 15.5H3L12 4z", "M12 10v4.5", "M12 17.2v.3"],
	info: ["M12 21a9 9 0 100-18 9 9 0 000 18z", "M12 11v5.5", "M12 7.6v.3"],
	good: ["M12 21a9 9 0 100-18 9 9 0 000 18z", "M8 12.4l2.8 2.8L16.2 9.6"],
	up: ["M12 19V5", "M6 11l6-6 6 6"],
	down: ["M12 5v14", "M6 13l6 6 6-6"],
	bank: ["M3 10l9-6 9 6", "M5 10v8", "M9.7 10v8", "M14.3 10v8", "M19 10v8", "M3 20h18"],
	cash: ["M3 7h18v10H3z", "M12 14.2a2.2 2.2 0 100-4.4 2.2 2.2 0 000 4.4z", "M6.5 10v.3", "M17.5 13.7v.3"],
	card: ["M3 6h18v12H3z", "M3 10h18", "M7 15h4"],
	chart: ["M4 20h16", "M7 20v-6", "M12 20V6", "M17 20v-9"],
	user: ["M12 12a4 4 0 100-8 4 4 0 000 8z", "M4.5 20a7.5 7.5 0 0115 0"],
	people: ["M9 11.5a3.5 3.5 0 100-7 3.5 3.5 0 000 7z", "M2.5 20a6.5 6.5 0 0113 0", "M16 4.6a3.5 3.5 0 010 6.8", "M18.2 14.6a6.5 6.5 0 013.3 5.4"],
	filter: ["M4 6h16", "M7 12h10", "M10 18h4"],
	bulb: ["M9 18h6", "M10 21h4", "M12 3a6 6 0 00-3.6 10.8c.6.5 1 1.3 1.1 2.2h5c.1-.9.5-1.7 1.1-2.2A6 6 0 0012 3z"],
	sun: ["M12 16.2a4.2 4.2 0 100-8.4 4.2 4.2 0 000 8.4z", "M12 2.6v2", "M12 19.4v2", "M2.6 12h2", "M19.4 12h2", "M5.35 5.35l1.4 1.4", "M17.25 17.25l1.4 1.4", "M5.35 18.65l1.4-1.4", "M17.25 6.75l1.4-1.4"],
	moon: ["M20.4 13.6A8.5 8.5 0 1110.4 3.6a6.7 6.7 0 0010 10z"],
	spark: ["M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3z"],
};

export function icon(name, extraClass) {
	const node = s("svg", {
		class: "icon" + (extraClass ? " " + extraClass : ""),
		viewBox: "0 0 24 24",
		"aria-hidden": "true",
		focusable: "false",
	});
	for (const d of ICONS[name] || []) {
		node.append(s("path", { d }));
	}
	return node;
}

// ----- money and dates ----------------------------------------------------

const INR = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR" });
const INR_WHOLE = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "1234.5" -> "₹1,234.50". Uses a real minus sign for negatives. */
export function money(value) {
	const n = Number(value);
	if (!Number.isFinite(n)) {
		return "-";
	}
	return (n < 0 ? "−" : "") + INR.format(Math.abs(n));
}

/** Short money for chart labels: no paise. */
export function moneyShort(value) {
	const n = Number(value);
	if (!Number.isFinite(n)) {
		return "-";
	}
	return (n < 0 ? "−" : "") + INR_WHOLE.format(Math.abs(n));
}

const pad = (n) => String(n).padStart(2, "0");

export function isoDate(date) {
	return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate());
}

export function todayIso() {
	return isoDate(new Date());
}

/** "2026-11-10" -> "10 Nov" (this year) or "10 Nov 2025". */
export function showDate(iso, alwaysYear) {
	const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || "");
	if (!m) {
		return iso || "";
	}
	const text = Number(m[3]) + " " + MONTHS[Number(m[2]) - 1];
	return alwaysYear || Number(m[1]) !== new Date().getFullYear() ? text + " " + m[1] : text;
}

export function monthName(year, monthIndex) {
	return MONTHS[monthIndex] + " " + year;
}

/**
 * Reads an amount the user typed. Returns the clean string ("1200.5") or null when it is not
 * "digits with at most 2 decimals". Commas, spaces and a rupee sign are forgiven.
 */
export function cleanAmount(text) {
	let value = String(text || "").replace(/[\s,₹]/g, "");
	if (!/^\d{1,9}(\.\d{1,2})?$/.test(value)) {
		return null;
	}
	value = value.replace(/^0+(?=\d)/, "");
	return value;
}

// ----- status messages ----------------------------------------------------

let toastTimer = null;

/** Says a short status line to screen readers and shows it for a moment. */
export function toast(message) {
	const live = document.getElementById("live");
	if (live) {
		live.textContent = "";
		window.setTimeout(() => {
			live.textContent = message;
		}, 30);
	}
	let node = document.getElementById("toast");
	if (!node) {
		node = h("div", { id: "toast", class: "toast", "aria-hidden": "true" });
		document.body.append(node);
	}
	// keep it above an open dialog (the top layer)
	const open = document.querySelector("dialog[open]");
	(open || document.body).append(node);
	node.textContent = message;
	node.hidden = false;
	window.clearTimeout(toastTimer);
	toastTimer = window.setTimeout(() => {
		node.hidden = true;
	}, 3200);
}

export function announce(message) {
	const live = document.getElementById("live");
	if (live) {
		live.textContent = "";
		window.setTimeout(() => {
			live.textContent = message;
		}, 30);
	}
}

// ----- form fields --------------------------------------------------------

let fieldCounter = 0;

/**
 * Builds label + input + error line. Returns { wrap, input }.
 * options: label, type, name, value, optional, hint, money, attrs, id
 */
export function field(options) {
	fieldCounter += 1;
	const id = options.id || "f" + fieldCounter + "-" + (options.name || "x");
	const input = h("input", {
		class: "input",
		id,
		name: options.name,
		type: options.type || "text",
		"aria-describedby": id + "-err" + (options.hint ? " " + id + "-hint" : ""),
		...(options.money ? { inputmode: "decimal", autocomplete: "off", placeholder: "0.00", maxlength: 14 } : {}),
		...(options.attrs || {}),
	});
	if (options.value !== undefined && options.value !== null) {
		input.value = options.value;
	}
	const label = h("label", { for: id }, options.label, options.optional ? h("span", { class: "optional" }, " (optional)") : null);
	const box = options.money ? h("div", { class: "money" }, input) : input;
	const wrap = h(
		"div",
		{ class: "field" },
		label,
		box,
		options.hint ? h("p", { class: "field-hint", id: id + "-hint" }, options.hint) : null,
		h("p", { class: "field-error", id: id + "-err", hidden: true }),
	);
	return { wrap, input };
}

/** A password field with a Show / Hide button. */
export function passwordField(options) {
	const made = field({ ...options, type: "password", attrs: { autocomplete: options.autocomplete, maxlength: 128, ...(options.attrs || {}) } });
	const box = h("div", { class: "reveal" });
	made.input.replaceWith(box);
	box.append(made.input, revealButton(made.input));
	return made;
}

export function revealButton(input) {
	const button = h("button", { type: "button", class: "reveal-btn", "aria-pressed": "false", "aria-label": "Show password" }, "Show");
	button.addEventListener("click", () => {
		const show = input.type === "password";
		input.type = show ? "text" : "password";
		button.textContent = show ? "Hide" : "Show";
		button.setAttribute("aria-pressed", String(show));
		button.setAttribute("aria-label", show ? "Hide password" : "Show password");
	});
	return button;
}

export function selectField(options) {
	fieldCounter += 1;
	const id = options.id || "f" + fieldCounter + "-" + (options.name || "x");
	const select = h("select", { class: "select", id, name: options.name, "aria-describedby": id + "-err" });
	for (const option of options.options) {
		const node = h("option", { value: option.value }, option.label);
		select.append(node);
	}
	if (options.value !== undefined && options.value !== null) {
		select.value = options.value;
	}
	const wrap = h(
		"div",
		{ class: "field" },
		h("label", { for: id }, options.label),
		select,
		h("p", { class: "field-error", id: id + "-err", hidden: true }),
	);
	return { wrap, input: select };
}

/** Radio buttons drawn as chips. Returns { wrap, value(), set(v) }. */
export function chipField(options) {
	fieldCounter += 1;
	const name = (options.name || "c") + fieldCounter;
	const group = h("div", { class: "chips" + (options.wrap ? " wrap" : "") });
	const inputs = [];
	for (const option of options.options) {
		const input = h("input", { type: "radio", name, value: option.value });
		input.checked = option.value === options.value;
		inputs.push(input);
		group.append(h("label", { class: "chip" }, input, h("span", null, option.label)));
	}
	const error = h("p", { class: "field-error", hidden: true });
	const wrap = h("fieldset", { class: "field" }, h("legend", null, options.label), group, error);
	return {
		wrap,
		inputs,
		error,
		value: () => {
			const on = inputs.find((i) => i.checked);
			return on ? on.value : null;
		},
		set: (value) => {
			for (const i of inputs) {
				i.checked = i.value === value;
			}
		},
	};
}

function errorNode(input) {
	return input.id ? document.getElementById(input.id + "-err") : null;
}

export function setFieldError(input, message) {
	const node = errorNode(input);
	if (message) {
		input.setAttribute("aria-invalid", "true");
		if (node) {
			node.textContent = message;
			node.hidden = false;
		}
	} else {
		input.removeAttribute("aria-invalid");
		if (node) {
			node.textContent = "";
			node.hidden = true;
		}
	}
}

/** The message box at the top of a form. kind: "problem" | "good" | "". */
export function setNotice(node, message, kind) {
	if (!node) {
		return;
	}
	if (!message) {
		node.hidden = true;
		node.textContent = "";
		return;
	}
	node.classList.add("notice");
	node.classList.toggle("problem", kind === "problem");
	node.classList.toggle("good", kind === "good");
	node.textContent = message;
	node.hidden = false;
}

export function clearErrors(form) {
	for (const input of form.querySelectorAll("[aria-invalid]")) {
		setFieldError(input, "");
	}
	for (const node of form.querySelectorAll(".field-error")) {
		node.hidden = true;
		node.textContent = "";
	}
	setNotice(form.querySelector(".form-notice"), "");
}

/**
 * Shows a failed call on a form: field messages under their inputs (inputs: { apiField: element }),
 * anything else in the form's notice box. Focus goes to the first wrong field.
 */
export function showError(form, error, inputs) {
	const notice = form.querySelector(".form-notice");
	let first = null;
	let placed = 0;
	let total = 0;
	if (error instanceof ApiError && error.fields) {
		for (const [name, message] of Object.entries(error.fields)) {
			total += 1;
			const input = inputs && inputs[name];
			if (input && errorNode(input)) {
				setFieldError(input, message);
				placed += 1;
				first = first || input;
			}
		}
	}
	if (total === 0 || placed < total) {
		setNotice(notice, errorText(error), "problem");
		if (!first && notice) {
			notice.setAttribute("tabindex", "-1");
			notice.focus({ preventScroll: false });
		}
	}
	if (first) {
		first.focus();
	}
	announce(first ? "Please check the form." : errorText(error));
}

/**
 * Runs an async action for a form submit: clears old errors, disables the buttons while waiting,
 * shows a failure on the form. Returns true when the action finished without an error.
 */
export async function submitting(form, action, inputs) {
	if (form.dataset.busy === "1") {
		return false;
	}
	clearErrors(form);
	const buttons = Array.from(form.querySelectorAll("button")).filter((b) => !b.disabled);
	const main = form.querySelector('button[type="submit"]');
	form.dataset.busy = "1";
	for (const b of buttons) {
		b.disabled = true;
	}
	if (main) {
		main.classList.add("busy");
	}
	try {
		const result = await action();
		return result !== false;
	} catch (error) {
		if (error && error.name === "AbortError") {
			return false;
		}
		if (!(error instanceof ApiError)) {
			console.error(error);
		}
		showError(form, error, typeof inputs === "function" ? inputs() : inputs);
		return false;
	} finally {
		delete form.dataset.busy;
		for (const b of buttons) {
			b.disabled = false;
		}
		if (main) {
			main.classList.remove("busy");
		}
	}
}

/** Reads a money input. Sets the field error and returns null when it is wrong. */
export function readAmount(input, options = {}) {
	const raw = input.value.trim();
	if (raw === "") {
		if (options.optional) {
			return options.blank === undefined ? "0" : options.blank;
		}
		setFieldError(input, "Enter an amount.");
		return null;
	}
	const value = cleanAmount(raw);
	if (value === null) {
		setFieldError(input, "Enter a plain amount like 120 or 49.50.");
		return null;
	}
	if (!options.allowZero && Number(value) === 0) {
		setFieldError(input, "Enter an amount above zero.");
		return null;
	}
	return value;
}

// ----- password rules (the server decides; this only guides) ---------------

const RULES = [
	{ id: "length", text: "10 to 64 characters", test: (p) => [...p].length >= 10 && [...p].length <= 64 },
	{ id: "lower", text: "A small letter (a-z)", test: (p) => /\p{Ll}/u.test(p) },
	{ id: "upper", text: "A capital letter (A-Z)", test: (p) => /\p{Lu}/u.test(p) },
	{ id: "digit", text: "A number (0-9)", test: (p) => /\p{Nd}/u.test(p) },
	{ id: "symbol", text: "A symbol such as @ or #", test: (p) => /[^\p{L}\p{Nd}]/u.test(p) },
];

/**
 * A live list of the password rules under a password input.
 * personal() may return { email, phone } so the "not your email name / phone" rule can be shown.
 */
export function passwordRules(input, personal) {
	const list = h("ul", { class: "rules", "aria-label": "Password rules" });
	const items = RULES.map((rule) => {
		const li = h("li", null, rule.text);
		list.append(li);
		return { rule, li };
	});
	let personalItem = null;
	if (personal) {
		personalItem = h("li", null, "Not your email name or mobile number");
		list.append(personalItem);
	}
	const update = () => {
		const value = input.value;
		let all = true;
		for (const { rule, li } of items) {
			const ok = value.length > 0 && rule.test(value);
			li.classList.toggle("ok", ok);
			all = all && ok;
		}
		if (personalItem) {
			const who = personal() || {};
			const name = String(who.email || "").split("@")[0].toLowerCase();
			const digits = String(who.phone || "").replace(/\D/g, "").slice(-10);
			const lower = value.toLowerCase();
			const bad = (name.length >= 3 && lower.includes(name)) || (digits.length === 10 && value.includes(digits));
			const ok = value.length > 0 && !bad;
			personalItem.classList.toggle("ok", ok);
			all = all && ok;
		}
		return all;
	};
	input.addEventListener("input", update);
	update();
	return { list, update };
}

// ----- dialogs --------------------------------------------------------------

/**
 * Opens a modal dialog (native <dialog>: focus stays inside, Esc closes, focus returns after).
 * build(body, close) fills it. Returns { close }.
 */
export function openDialog(title, build, options = {}) {
	const opener = document.activeElement;
	const titleId = "dlg-" + Date.now();
	const body = h("div", { class: "dialog-body" });
	const dialog = h(
		"dialog",
		{ class: "dialog", "aria-labelledby": titleId },
		h(
			"div",
			{ class: "dialog-head" },
			h("h2", { id: titleId }, title),
			h("button", { type: "button", class: "icon-btn", "aria-label": "Close", on: { click: () => dialog.close() } }, icon("close")),
		),
		body,
	);
	const close = () => {
		if (dialog.open) {
			dialog.close();
		}
	};
	let downOnBackdrop = false;
	dialog.addEventListener("mousedown", (event) => {
		downOnBackdrop = event.target === dialog;
	});
	dialog.addEventListener("click", (event) => {
		if (event.target === dialog && downOnBackdrop) {
			close();
		}
	});
	dialog.addEventListener("close", () => {
		dialog.remove();
		if (options.onClose) {
			options.onClose();
		}
		if (opener && document.contains(opener) && typeof opener.focus === "function") {
			opener.focus();
		}
	});
	document.body.append(dialog);
	build(body, close);
	dialog.showModal();
	const first = body.querySelector("[data-autofocus]") || (options.typeNow === false ? null : body.querySelector("input:not([type=radio]), select, textarea"));
	if (first) {
		first.focus();
	}
	return { close, dialog };
}

export function formNotice() {
	return h("p", { class: "notice form-notice", role: "alert", hidden: true });
}
