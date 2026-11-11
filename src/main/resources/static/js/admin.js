// The "Admin" section: only for an administrator (user.admin from /api/auth/me). Counts only,
// never anybody's details. The numbers load when the dashboard opens and on "Refresh" only:
// a timer would keep the session alive and so defeat the idle logout.

import { api, errorText } from "/js/api.js";
import { clear, announce } from "/js/ui.js";
import { countChart } from "/js/charts.js";

const $ = (id) => document.getElementById(id);

const state = { data: null, loading: false };

const count = (value) => (Number(value) || 0).toLocaleString("en-IN");

function plus(value, when) {
	const n = Number(value) || 0;
	return n > 0 ? "+" + count(n) + " " + when : "None new " + when;
}

function chartWidth() {
	return $("admin-chart").clientWidth || 300;
}

function drawChart() {
	const holder = $("admin-chart");
	clear(holder);
	const days = (state.data && state.data.signupsLast30Days) || [];
	if (days.length > 0) {
		holder.append(countChart(days, chartWidth(), "sign-up"));
	}
}

function paint(data) {
	const users = data.users || {};
	const activity = data.activity || {};
	$("adm-users").textContent = count(users.total);
	$("adm-users-note").textContent = plus(users.newLast7Days, "in 7 days");
	$("adm-online").textContent = count(activity.onlineNow);
	$("adm-active").textContent = count(activity.activeLast7Days);
	$("adm-new").textContent = count(users.newLast30Days);
	$("adm-new-note").textContent = plus(users.newToday, "today");
	$("adm-wait").textContent = "Waiting for code: " + count(data.waitingForCode);
	const at = new Date(data.generatedAt);
	$("adm-time").textContent = Number.isNaN(at.getTime())
		? ""
		: "Updated at " + at.toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit" });
	$("admin-body").hidden = false;
	drawChart();
}

async function load(byHand) {
	if (state.loading) {
		return;
	}
	state.loading = true;
	const button = $("admin-refresh");
	// aria-disabled, not disabled: a disabled button loses the keyboard focus. state.loading blocks a second request.
	button.setAttribute("aria-disabled", "true");
	button.classList.add("busy");
	$("admin").setAttribute("aria-busy", "true");
	try {
		const data = await api.get("/api/admin/stats");
		state.data = data;
		$("admin-error").hidden = true;
		paint(data);
		if (byHand) {
			announce("Admin numbers updated.");
		}
	} catch (error) {
		if (error && error.status === 404) {
			// this account is no longer an administrator (ADMIN_EMAILS changed): show nothing, not the old numbers
			state.data = null;
			$("admin").hidden = true;
			announce("You are not an admin any more.");
			return;
		}
		$("admin-error").textContent = "Cannot load the admin numbers. " + errorText(error);
		$("admin-error").hidden = false;
	} finally {
		state.loading = false;
		button.removeAttribute("aria-disabled");
		button.classList.remove("busy");
		$("admin").setAttribute("aria-busy", "false");
	}
}

/** Call once, only when user.admin is true. Other users never show the section or ask for the numbers. */
export function setupAdmin(user) {
	if (!user || user.admin !== true) {
		return;
	}
	$("admin").hidden = false;
	$("admin-refresh").addEventListener("click", () => load(true));

	// the chart is drawn at the real width: draw it again when that changes (no new request)
	let lastWidth = 0;
	let resizeTimer = null;
	window.addEventListener("resize", () => {
		window.clearTimeout(resizeTimer);
		resizeTimer = window.setTimeout(() => {
			const width = chartWidth();
			if (state.data && width !== lastWidth) {
				lastWidth = width;
				drawChart();
			}
		}, 150);
	});

	load(false);
}
