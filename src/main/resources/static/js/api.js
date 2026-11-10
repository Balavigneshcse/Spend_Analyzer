// The one place that talks to the server. JSON in, JSON out, same-origin cookie.
// It also copes with the free host being asleep: long timeout, a "waking up" banner, retries.

const TIMEOUT_MS = 90000;
const WAKE_AFTER_MS = 4000;
const RETRY_DELAYS_MS = [1000, 2000, 4000, 8000, 12000, 15000, 15000];

export class ApiError extends Error {
	constructor(status, body) {
		super((body && body.message) || "Something went wrong. Please try again.");
		this.name = "ApiError";
		this.status = status;
		this.code = (body && body.error) || "error";
		this.fields = (body && body.fields) || null;
		this.retryAfterSeconds = body && Number.isFinite(body.retryAfterSeconds) ? body.retryAfterSeconds : null;
	}
}

let unauthorizedHandler = null;

/** The dashboard sets this: any "not logged in" answer sends the user to the login page. */
export function onUnauthorized(handler) {
	unauthorizedHandler = handler;
}

// ----- "waking up" banner -------------------------------------------------

let slowCount = 0;

function showWake(show) {
	const banner = document.getElementById("wake");
	if (banner) {
		banner.hidden = !show;
	}
}

function watchSlow() {
	let fired = false;
	const timer = setTimeout(() => {
		fired = true;
		slowCount += 1;
		showWake(true);
	}, WAKE_AFTER_MS);
	return () => {
		clearTimeout(timer);
		if (fired) {
			slowCount -= 1;
			if (slowCount <= 0) {
				slowCount = 0;
				showWake(false);
			}
		}
	};
}

// ----- one request --------------------------------------------------------

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function asleep(status) {
	return new ApiError(status, {
		error: "unavailable",
		message: "The server is waking up. Please try again in a minute.",
	});
}

async function send(method, path, body, signal, raw) {
	const controller = new AbortController();
	let timedOut = false;
	const timer = setTimeout(() => {
		timedOut = true;
		controller.abort();
	}, TIMEOUT_MS);
	const cancel = () => controller.abort();
	if (signal) {
		if (signal.aborted) {
			controller.abort();
		}
		signal.addEventListener("abort", cancel, { once: true });
	}
	const headers = { Accept: "application/json" };
	if (body !== undefined) {
		headers["Content-Type"] = "application/json";
	}
	let response;
	try {
		response = await fetch(path, {
			method,
			credentials: "same-origin",
			cache: "no-store",
			headers,
			body: body === undefined ? undefined : JSON.stringify(body),
			signal: controller.signal,
		});
	} catch (cause) {
		if (signal && signal.aborted && !timedOut) {
			const stopped = new Error("cancelled");
			stopped.name = "AbortError";
			throw stopped;
		}
		throw new ApiError(0, {
			error: timedOut ? "timeout" : "network",
			message: timedOut
				? "The server took too long. Please try again."
				: "Cannot reach the server. Check your internet and try again.",
		});
	} finally {
		clearTimeout(timer);
		if (signal) {
			signal.removeEventListener("abort", cancel);
		}
	}

	const type = response.headers.get("Content-Type") || "";
	if (response.ok && raw) {
		return response;
	}
	if (response.status === 204) {
		return null;
	}
	let data = null;
	if (type.includes("json")) {
		try {
			data = await response.json();
		} catch (ignored) {
			data = null;
		}
	}
	if (response.ok) {
		if (data === null) {
			throw asleep(503); // a web page where JSON was expected: the host's "starting" page
		}
		return data;
	}
	if (data === null || typeof data !== "object" || !data.message) {
		if (response.status === 502 || response.status === 503 || response.status === 504) {
			throw asleep(response.status); // the host's own error page, not our JSON
		}
		throw new ApiError(response.status, null);
	}
	if (data.retryAfterSeconds === undefined) {
		const header = Number.parseInt(response.headers.get("Retry-After") || "", 10);
		if (Number.isFinite(header)) {
			data.retryAfterSeconds = header;
		}
	}
	throw new ApiError(response.status, data);
}

/**
 * "all": reading, or a write that carries a clientId - safe to send again after any failure.
 * "asleep": other writes - only when the host answered 502/503 itself, so the app never saw the call.
 */
function canRetry(error, mode) {
	if (!(error instanceof ApiError) || mode === "none") {
		return false;
	}
	if (mode === "all") {
		return error.code === "network" || error.code === "unavailable";
	}
	return error.code === "unavailable" && (error.status === 502 || error.status === 503);
}

/**
 * @param {string} method
 * @param {string} path
 * @param {object} [options] body: JSON body; retry: true = safe to send again after any failure
 *                 (default for GET), false = never; signal: AbortSignal; raw: resolve with the Response
 */
export async function request(method, path, options = {}) {
	const { body, signal, raw = false } = options;
	const mode = options.retry === true || (options.retry === undefined && method === "GET") ? "all" : options.retry === false ? "none" : "asleep";
	const done = watchSlow();
	try {
		for (let attempt = 0; ; attempt += 1) {
			try {
				return await send(method, path, body, signal, raw);
			} catch (error) {
				if (error instanceof ApiError && error.status === 401 && error.code === "unauthorized" && unauthorizedHandler) {
					unauthorizedHandler();
					return await new Promise(() => {}); // the page is leaving
				}
				if (!canRetry(error, mode) || attempt >= RETRY_DELAYS_MS.length) {
					throw error;
				}
				await sleep(RETRY_DELAYS_MS[attempt]);
				if (signal && signal.aborted) {
					const stopped = new Error("cancelled");
					stopped.name = "AbortError";
					throw stopped;
				}
			}
		}
	} finally {
		done();
	}
}

export const api = {
	get: (path, options) => request("GET", path, options),
	post: (path, body, options) => request("POST", path, { ...options, body: body === undefined ? {} : body }),
	put: (path, body, options) => request("PUT", path, { ...options, body }),
	patch: (path, body, options) => request("PATCH", path, { ...options, body }),
	del: (path, body, options) => request("DELETE", path, { ...options, body }),
};

/** A new id for one form submit. The caller keeps it when it sends the same submit again. */
export function newClientId() {
	if (window.crypto && typeof window.crypto.randomUUID === "function") {
		return window.crypto.randomUUID();
	}
	const b = window.crypto.getRandomValues(new Uint8Array(16));
	b[6] = (b[6] & 0x0f) | 0x40;
	b[8] = (b[8] & 0x3f) | 0x80;
	const hex = Array.from(b, (x) => x.toString(16).padStart(2, "0"));
	return (
		hex.slice(0, 4).join("") +
		"-" +
		hex.slice(4, 6).join("") +
		"-" +
		hex.slice(6, 8).join("") +
		"-" +
		hex.slice(8, 10).join("") +
		"-" +
		hex.slice(10).join("")
	);
}

/** Words for a wait such as "42 seconds" or "3 minutes". */
export function waitWords(seconds) {
	const s = Math.max(1, Math.ceil(seconds));
	if (s < 90) {
		return s === 1 ? "1 second" : s + " seconds";
	}
	const m = Math.ceil(s / 60);
	if (m < 90) {
		return m + " minutes";
	}
	const hours = Math.ceil(m / 60);
	return hours === 1 ? "1 hour" : hours + " hours";
}

/** The sentence to show for an error, with the wait time when the server gave one. */
export function errorText(error) {
	if (!(error instanceof ApiError)) {
		return "Something went wrong. Please try again.";
	}
	if (error.status === 429 && error.retryAfterSeconds) {
		const base = error.message.replace(/\s*Please try again later\.?\s*$/i, "").trim();
		return (base || "Too many attempts.") + " Try again in " + waitWords(error.retryAfterSeconds) + ".";
	}
	return error.message;
}
