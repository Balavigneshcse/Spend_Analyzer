// Logs the user out after a few minutes with no activity, the way a bank site does.
// The server is the real control: it refuses a session that sent no request for longer than the
// limit (plus 60 seconds). This module keeps the server in step with real activity (a small ping
// while the user reads or types), warns before the end, and logs out when the time is up.
// Dashboard only. All times are wall-clock (Date.now), so a phone that slept or a tab the browser
// slowed down still logs out on time when it wakes.

import { h, openDialog } from "/js/ui.js";
import { store, remember, recall } from "/js/store.js";

const IDLE_URL = "/login.html?reason=idle";
const OUT_URL = "/login.html#out";

const PING_EVERY_MS = 60000; // the server's 60 s grace covers this
const PING_RETRY_MS = 5000; // a ping that failed is sent again after this
const PING_WAIT_MS = 15000; // a ping with no answer by then counts as failed
const SHARE_EVERY_MS = 2000; // how often this tab tells the other tabs about activity
const MOVE_EVERY_MS = 1000; // mousemove fires all the time: count it at most once a second
const CHECK_EVERY_MS = 1000;
const LOGOUT_WAIT_MS = 5000;

const ACTIVITY = ["pointerdown", "keydown", "wheel", "touchstart", "scroll", "mousemove"];
// keys that move around or press a button in the warning (Esc closes it, which counts as "stay")
const DIALOG_KEYS = new Set(["Tab", "Shift", "Enter", " ", "Escape", "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"]);
const SHARED_KEY = "idle.active"; // store.js adds the "sa." prefix
const MESSAGE_KEY = "idle.message";

let limitMs = 0; // 0 until startIdle
let warnMs = 0; // the warning shows this long before the end
let lastActivity = Date.now();
let lastPing = Date.now(); // the page's first API calls already counted on the server
let lastMove = 0;
let lastShared = 0;
let shareTimer = null;
let pingTimer = null; // set while a ping is owed for activity the server has not seen yet
let pinging = false;
let checkTimer = null;
let warning = null; // { close, count, quiet }
let leaving = false;
let logoutClicked = false;
let channel = null;

// ----- talking to the other tabs ---------------------------------------------

function post(message) {
	if (channel) {
		try {
			channel.postMessage(message);
			return;
		} catch (ignored) {
			// closed channel: fall through
		}
	}
	// no BroadcastChannel: the storage event reaches the other tabs instead
	remember(MESSAGE_KEY, JSON.stringify({ ...message, n: Math.random() }));
}

function receive(message) {
	if (!message || leaving) {
		return;
	}
	if (message.type === "active" && Number.isFinite(message.at)) {
		active(message.at, false);
	} else if (message.type === "ping" && Number.isFinite(message.at)) {
		lastPing = Math.min(Math.max(lastPing, message.at), Date.now());
	} else if (message.type === "logout") {
		// another tab has already ended the session
		leave(message.reason === "out" ? OUT_URL : IDLE_URL);
	}
}

/** Picks up activity another tab wrote, even if its message has not arrived yet. */
function sync(now) {
	const shared = Number(recall(SHARED_KEY));
	// a time after now was written before the clock went back: it is not new activity
	if (Number.isFinite(shared) && shared > lastActivity && shared <= now) {
		lastActivity = shared;
	}
}

function share() {
	const since = Date.now() - lastShared;
	if (since >= SHARE_EVERY_MS || since < 0) {
		// (below 0: the clock went back)
		sendShare();
	} else if (!shareTimer) {
		// trailing send, so the other tabs learn the time of the LAST activity of a burst
		shareTimer = window.setTimeout(sendShare, SHARE_EVERY_MS - since);
	}
}

function sendShare() {
	window.clearTimeout(shareTimer);
	shareTimer = null;
	lastShared = Date.now();
	remember(SHARED_KEY, String(lastActivity));
	post({ type: "active", at: lastActivity });
}

// ----- activity --------------------------------------------------------------

function onActivity(event) {
	if (leaving || !limitMs) {
		return;
	}
	const now = Date.now();
	if (event.type === "mousemove") {
		if (now - lastMove < MOVE_EVERY_MS && now >= lastMove) {
			return;
		}
		lastMove = now;
	}
	// Inside the warning the mouse and the keys that move between or press its buttons do not
	// count: moving to "Log out" must not close it. Typing any other key means "still here".
	if (warning && event.target instanceof Node && warning.dialog.contains(event.target)) {
		if (event.type !== "keydown" || DIALOG_KEYS.has(event.key)) {
			return;
		}
	}
	if (timeIsUp(now)) {
		return;
	}
	active(now, true);
}

function onVisible() {
	if (leaving || !limitMs) {
		return;
	}
	if (document.visibilityState !== "visible") {
		// a phone may freeze the tab now: send a ping that activity is still owed, before that
		if (pingTimer) {
			ping(true);
		}
		return;
	}
	// back after a sleep: if the time ran out meanwhile, log out at once, without the warning
	if (!timeIsUp(Date.now())) {
		active(Date.now(), true);
	}
}

/** True (and logging out) when the limit has passed since the last activity in any tab. */
function timeIsUp(now) {
	sync(now);
	if (lastActivity > now) {
		lastActivity = now; // the clock went back: count it as "just active", never as "never idle"
	}
	if (now - lastActivity >= limitMs) {
		logOut();
		return true;
	}
	return false;
}

function active(at, here) {
	// never later than now: a time from the future (another tab, a clock that went back) would stop the logout
	lastActivity = Math.min(Math.max(lastActivity, at), Date.now());
	// an old time from another tab must not close a warning that is still due
	if (warning && Date.now() - lastActivity < limitMs - warnMs) {
		closeWarning();
	}
	if (here) {
		share();
		pingSoon();
	}
}

// ----- keep-alive ------------------------------------------------------------

/** At most one ping a minute, and only after activity (this is only called on activity). */
function pingSoon() {
	if (pingTimer || pinging || leaving) {
		return;
	}
	// at most a minute, even when the clock went back
	const wait = Math.min(PING_EVERY_MS, Math.max(0, lastPing + PING_EVERY_MS - Date.now()));
	pingTimer = window.setTimeout(ping, wait);
}

/** keepalive: the page is being hidden or closed, so the request must outlive it. */
async function ping(keepalive = false) {
	window.clearTimeout(pingTimer);
	pingTimer = null;
	if (leaving || pinging) {
		return;
	}
	pinging = true;
	const sent = Date.now();
	let response = null;
	const controller = new AbortController();
	const timer = window.setTimeout(() => controller.abort(), PING_WAIT_MS);
	try {
		// no body: nothing to send, and the same-origin filter lets an empty POST through
		response = await fetch("/api/auth/ping", {
			method: "POST",
			credentials: "same-origin",
			cache: "no-store",
			keepalive: keepalive === true,
			signal: controller.signal,
		});
	} catch (ignored) {
		// offline for a moment, or no answer in time: tried again below
	} finally {
		window.clearTimeout(timer);
		pinging = false;
	}
	if (leaving) {
		return;
	}
	if (response && response.status === 401) {
		leave(loginUrl());
		return;
	}
	if (!response || !response.ok) {
		// the server did not see this activity: try again soon (the logout at the limit stops this)
		pingTimer = window.setTimeout(ping, PING_RETRY_MS);
		return;
	}
	// only a ping the server answered counts, here and in the other tabs
	lastPing = Math.max(Math.min(lastPing, Date.now()), sent);
	post({ type: "ping", at: lastPing });
}

// ----- the clock -------------------------------------------------------------

function check() {
	if (leaving || !limitMs) {
		return;
	}
	const now = Date.now();
	if (timeIsUp(now)) {
		return;
	}
	if (now - lastActivity >= limitMs - warnMs) {
		showWarning();
		showCount(now);
	} else if (warning) {
		closeWarning();
	}
}

function secondsWords(seconds) {
	return seconds === 1 ? "1 second" : seconds + " seconds";
}

function secondsLeft(now) {
	return Math.max(0, Math.ceil((lastActivity + limitMs - now) / 1000));
}

function showCount(now) {
	if (warning) {
		warning.count.textContent = secondsWords(secondsLeft(now));
	}
}

// ----- the warning -----------------------------------------------------------

function showWarning() {
	if (warning || leaving) {
		return;
	}
	const count = h("span", { class: "idle-count" }, secondsWords(secondsLeft(Date.now())));
	const state = { count, quiet: false, close: null, dialog: null };
	warning = state;
	const opened = openDialog(
		"Are you still there?",
		(body, close) => {
			const textId = "idle-text-" + Date.now();
			body.append(
				h("p", { id: textId }, "For your safety you will be logged out in ", count, "."),
				h(
					"div",
					{ class: "dialog-actions" },
					h("button", { type: "button", class: "btn secondary", on: { click: () => logOut("out") } }, "Log out"),
					h("button", { type: "button", class: "btn", "data-autofocus": true, on: { click: close } }, "Stay logged in"),
				),
			);
			// read out once when it opens (the page behind a modal dialog cannot announce anything);
			// the number is not a live region, so it is not read every second
			const dialog = body.parentElement;
			dialog.setAttribute("role", "alertdialog");
			dialog.setAttribute("aria-describedby", textId);
			state.dialog = dialog;
		},
		{
			onClose: () => {
				warning = null;
				// "Stay logged in", Esc, the X or a click beside it: the user is here
				if (!state.quiet && !leaving) {
					stay();
				}
			},
		},
	);
	state.close = opened.close;
	state.dialog = opened.dialog;
}

/** Activity somewhere closed the warning: that activity already counts. */
function closeWarning() {
	const open = warning;
	if (open) {
		open.quiet = true;
		open.close();
	}
}

function stay() {
	active(Date.now(), true);
	sendShare(); // at once, so the warning closes in the other tabs too
	ping();
}

// ----- leaving ---------------------------------------------------------------

/** Takes the user's data off the screen and out of memory before anything else. */
function forget() {
	for (const id of ["dash", "onboarding", "account", "loading", "load-error"]) {
		const node = document.getElementById(id);
		if (node) {
			node.hidden = true;
		}
	}
	// any open dialog (a form, a friend's entries) goes too, and can no longer save anything
	for (const dialog of document.querySelectorAll("dialog")) {
		dialog.remove();
	}
	store.user = null;
	store.balances = null;
	store.debts = null;
	store.meta = { categories: [], paymentModes: [], accounts: [] };
}

function stopTimers() {
	window.clearInterval(checkTimer);
	window.clearTimeout(pingTimer);
	window.clearTimeout(shareTimer);
	checkTimer = pingTimer = shareTimer = null;
}

function leave(url) {
	if (leaving) {
		return;
	}
	leaving = true;
	stopTimers();
	closeWarning();
	forget();
	window.location.replace(url);
}

/** reason "idle": the time ran out; "out": the user pressed "Log out" in the warning. */
async function logOut(reason = "idle") {
	if (leaving) {
		return;
	}
	leaving = true;
	stopTimers();
	closeWarning();
	forget();
	const controller = new AbortController();
	const timer = window.setTimeout(() => controller.abort(), LOGOUT_WAIT_MS);
	// one more try if the first fails (the login page tries once more too)
	for (let attempt = 0; attempt < 2 && !controller.signal.aborted; attempt += 1) {
		try {
			const response = await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin", cache: "no-store", signal: controller.signal });
			if (response.ok) {
				break;
			}
		} catch (ignored) {
			// offline or no answer in time
		}
	}
	window.clearTimeout(timer);
	// only now: a tab that went to the login page earlier could still find the session alive
	post({ type: "logout", reason });
	window.location.replace(reason === "out" ? OUT_URL : IDLE_URL);
}

// ----- public ----------------------------------------------------------------

/**
 * Where a "not logged in" answer should send the user: with the idle notice when this page
 * knows the user has been away (at least until the warning time), otherwise the plain login page.
 */
export function loginUrl() {
	if (!limitMs) {
		return "/login.html";
	}
	const now = Date.now();
	sync(now);
	return now - lastActivity >= limitMs - warnMs ? IDLE_URL : "/login.html";
}

/** Starts watching. minutes comes from GET /api/auth/config, so the page and the server agree. */
export function startIdle(minutes) {
	const value = Number(minutes);
	if (!Number.isFinite(value) || value <= 0) {
		return;
	}
	const first = !limitMs;
	limitMs = value * 60000;
	// a minute of warning; half the time when the limit itself is under two minutes
	warnMs = limitMs < 120000 ? limitMs / 2 : 60000;
	if (!first) {
		return;
	}
	lastActivity = Date.now(); // opening the page is activity
	sendShare();
	if (typeof window.BroadcastChannel === "function") {
		channel = new window.BroadcastChannel("sa-idle");
		channel.addEventListener("message", (event) => receive(event.data));
	}
	window.addEventListener("storage", (event) => {
		if (event.key === "sa." + SHARED_KEY) {
			const at = Number(event.newValue);
			if (Number.isFinite(at) && !leaving) {
				active(Math.min(at, Date.now()), false);
			}
		} else if (event.key === "sa." + MESSAGE_KEY && !channel && event.newValue) {
			try {
				receive(JSON.parse(event.newValue));
			} catch (ignored) {
				// not ours
			}
		}
	});
	for (const type of ACTIVITY) {
		window.addEventListener(type, onActivity, { capture: true, passive: true });
	}
	document.addEventListener("visibilitychange", onVisible);
	window.addEventListener("pageshow", onVisible);
	// the account menu's "Log out" (account.js) ends the session for every tab: tell them once it is done
	document.addEventListener(
		"click",
		(event) => {
			if (event.target instanceof Element && event.target.closest("#menu-logout")) {
				logoutClicked = true;
			}
		},
		true,
	);
	window.addEventListener("pagehide", () => {
		if (logoutClicked && !leaving) {
			post({ type: "logout", reason: "out" });
		} else if (pingTimer && !leaving) {
			ping(true); // closing or leaving the page: the activity still owed a ping must reach the server
		}
	});
	checkTimer = window.setInterval(check, CHECK_EVERY_MS);
}
