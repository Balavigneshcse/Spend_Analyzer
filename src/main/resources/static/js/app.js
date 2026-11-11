// The one dashboard: loads the user, then shows either the first-time step or everything.

import { api, onUnauthorized, errorText } from "/js/api.js";
import { store, setBalances } from "/js/store.js";
import { setupAccount, paintUser } from "/js/account.js";
import { setupBalances } from "/js/balances.js";
import { setupFriends } from "/js/friends.js";
import { setupSpend } from "/js/spend.js";
import { setupEntries } from "/js/entries.js";
import { setupAnalysis } from "/js/analysis.js";
import { setupAdmin } from "/js/admin.js";
import { startIdle, loginUrl } from "/js/idle.js";

const $ = (id) => document.getElementById(id);

onUnauthorized(() => {
	// with the "no activity" notice when the idle watch knows the user was away
	window.location.replace(loginUrl());
});

let dashReady = false;

function showDash() {
	$("onboarding").hidden = true;
	$("dash").hidden = false;
	if (!dashReady) {
		dashReady = true;
		setupFriends();
		setupSpend();
		setupEntries();
		setupAnalysis();
		setupAdmin(store.user); // does nothing unless /api/auth/me said admin: true
	}
}

function showOnboarding() {
	$("dash").hidden = true;
	$("onboarding").hidden = false;
	$("open-bank").focus();
}

async function start() {
	$("loading").hidden = false;
	$("load-error").hidden = true;
	try {
		const [me, meta, balances, config] = await Promise.all([
			api.get("/api/auth/me"),
			api.get("/api/meta"),
			api.get("/api/balances"),
			api.get("/api/auth/config"),
		]);
		startIdle(config.idleMinutes);
		store.user = me.user;
		store.meta = meta;
		paintUser(me.user);
		$("loading").hidden = true;
		setBalances(balances);
		if (balances.onboarded) {
			showDash();
		} else {
			showOnboarding();
		}
	} catch (error) {
		$("loading").hidden = true;
		$("load-error-text").textContent = "Cannot load your account. " + errorText(error);
		$("load-error").hidden = false;
	}
}

setupAccount();
setupBalances(() => {
	showDash();
	window.scrollTo(0, 0);
	$("spend-what").focus();
});
$("load-retry").addEventListener("click", start);
start();
