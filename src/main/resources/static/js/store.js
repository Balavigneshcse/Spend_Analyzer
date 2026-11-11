// What the dashboard knows right now, and a tiny event bus between its parts.
// Nothing here is saved in the browser; only two harmless choices go to localStorage.

import { api, ApiError, newClientId } from "/js/api.js";

export const store = {
	user: null,
	meta: { categories: [], paymentModes: [], accounts: [] },
	balances: null,
	debts: null, // GET /api/debts: what is owed to each friend; null until it has loaded
};

const listeners = new Map();

export function on(event, handler) {
	if (!listeners.has(event)) {
		listeners.set(event, []);
	}
	listeners.get(event).push(handler);
}

export function emit(event, data) {
	for (const handler of listeners.get(event) || []) {
		handler(data);
	}
}

export function setBalances(balances) {
	if (balances) {
		store.balances = balances;
		emit("balances", balances);
	}
}

export function setDebts(debts) {
	if (debts) {
		store.debts = debts;
		emit("debts", debts);
	}
}

let changeTimer = null;

/** Call after any change to entries or balances: the list and the analysis reload shortly after. */
export function dataChanged() {
	window.clearTimeout(changeTimer);
	changeTimer = window.setTimeout(() => emit("changed"), 300);
}

export function categoryLabel(code) {
	const found = store.meta.categories.find((c) => c.code === code);
	return found ? found.label : code || "";
}

export function modeLabel(code) {
	const found = store.meta.paymentModes.find((m) => m.code === code);
	return found ? found.label : code || "";
}

/** The page's own plain names for the three balances. */
export const ACCOUNT_NAMES = { BANK: "Bank balance", CASH: "Cash in hand", CREDIT_CARD: "Credit card due" };

export function accountName(code) {
	return ACCOUNT_NAMES[code] || code || "";
}

// ----- harmless UI choices ---------------------------------------------------

export function remember(key, value) {
	try {
		window.localStorage.setItem("sa." + key, value);
	} catch (ignored) {
		// private mode: fine
	}
}

export function recall(key) {
	try {
		return window.localStorage.getItem("sa." + key);
	} catch (ignored) {
		return null;
	}
}

// ----- saving an entry -------------------------------------------------------

/**
 * Makes a sender for one form. It gives every new submit a fresh clientId and uses the SAME id
 * again when the same values are sent again after a failure, so nothing is ever counted twice.
 */
export function entrySender() {
	let lastKey = null;
	let lastId = null;
	return async function sendEntry(fields) {
		const key = JSON.stringify(fields);
		if (key !== lastKey || lastId === null) {
			lastKey = key;
			lastId = newClientId();
		}
		try {
			const answer = await api.post("/api/entries", { clientId: lastId, ...fields }, { retry: true });
			lastKey = null;
			lastId = null;
			return answer;
		} catch (error) {
			if (error instanceof ApiError && error.status === 400) {
				// the server refused these values: the next try is a new submit
				lastKey = null;
				lastId = null;
			}
			throw error;
		}
	};
}
