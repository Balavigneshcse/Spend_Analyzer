// The Analysis section: one call to /api/analysis per period, drawn as calm blocks.
// Every block copes with "no data yet" by showing one plain line.

import { api, errorText } from "/js/api.js";
import { h, icon, clear, money, showDate } from "/js/ui.js";
import { on, remember, recall } from "/js/store.js";
import { barList, splitBar, dailyChart, rangeChart, meter } from "/js/charts.js";
import { paintSpent } from "/js/balances.js";
import { showNeedsChecking } from "/js/entries.js";

const $ = (id) => document.getElementById(id);

const PERIODS = ["THIS_WEEK", "THIS_MONTH", "LAST_MONTH", "LAST_30_DAYS", "ALL_TIME"];
const NOT_YET = "Add a few more spends to see this.";

const state = { period: "THIS_MONTH", data: null, loadNo: 0, abort: null };

function block(title, wide, ...children) {
	return h("section", { class: "panel block" + (wide ? " wide" : "") }, title ? h("h3", null, title) : null, ...children);
}

function empty(text) {
	return h("p", { class: "empty" }, text || NOT_YET);
}

const percentText = (value) => (Number(value) || 0).toLocaleString("en-IN", { maximumFractionDigits: 1 }) + "%";

// ----- blocks ----------------------------------------------------------------

function summaryBlock(data) {
	const totals = data.totals || {};
	const count = Number(totals.count) || 0;
	const stats = h("dl", { class: "stats" });
	const stat = (name, value, sub) => {
		stats.append(h("div", { class: "stat" }, h("dt", null, name), h("dd", { class: "num" }, value, sub ? h("span", { class: "sub" }, sub) : null)));
	};
	stat("Spent", money(totals.spent || 0));
	stat("Spends", String(count));
	stat("Each day, about", money(totals.avgPerDay || 0));
	if (totals.biggestSpend) {
		stat("Biggest spend", money(totals.biggestSpend.amount), totals.biggestSpend.description + ", " + showDate(totals.biggestSpend.date));
	} else {
		stat("Biggest spend", "-");
	}
	const period = data.period || {};
	const range = period.from && period.to ? showDate(period.from, true) + " to " + showDate(period.to, true) : "";
	return block(
		(period.label || "Summary") + (range ? " - " + range : ""),
		true,
		stats,
		h("p", { class: "summary-text" }, data.summary || "Add your spends and a short summary shows here."),
	);
}

function healthBlock(health) {
	if (!health) {
		return block("Money health score", false, empty());
	}
	const parts = h("ul", { class: "parts" });
	for (const part of health.components || []) {
		const max = Number(part.max) || 0;
		const points = Number(part.points) || 0;
		parts.append(
			h(
				"li",
				{ class: "part" },
				h("div", { class: "part-top" }, h("span", null, part.name), h("span", { class: "num" }, points + " of " + max)),
				meter(max > 0 ? points / max : 0, 1, "meter"),
				part.note ? h("p", { class: "sub" }, part.note) : null,
			),
		);
	}
	return block(
		"Money health score",
		false,
		h("div", { class: "score" }, h("span", { class: "score-num num" }, String(health.score)), h("span", { class: "score-of" }, "out of 100"), h("span", { class: "score-band" }, health.band || "")),
		meter((Number(health.score) || 0) / 100, 1, "meter"),
		health.provisional ? h("p", { class: "early" }, h("span", { class: "tag" }, "Early estimate"), h("span", { class: "sub" }, " It gets better as you add spends.")) : null,
		(health.components || []).length ? parts : null,
	);
}

function runwayBlock(runway) {
	if (!runway) {
		return block("How long your money lasts", false, empty());
	}
	const days = Number(runway.daysLeft) || 0;
	return block(
		"How long your money lasts",
		false,
		h(
			"div",
			{ class: "figures" },
			h("div", null, h("p", { class: "figure-num num" }, days + (days === 1 ? " day" : " days")), h("p", { class: "figure-label" }, "Days left at this speed")),
			h("div", null, h("p", { class: "figure-num num" }, money(runway.safePerDay)), h("p", { class: "figure-label" }, "Safe to spend per day")),
		),
		h(
			"p",
			{ class: "after" },
			"You have " + money(runway.available) + " in bank and cash. You spend about " + money(runway.dailyBurn) + " a day. " + (Number(runway.daysToMonthEnd) === 1 ? "1 day" : runway.daysToMonthEnd + " days") + " to the end of the month.",
		),
	);
}

function forecastBlock(forecast, width) {
	if (!forecast) {
		return block("Month-end forecast", false, empty());
	}
	return block(
		"Month-end forecast",
		false,
		h("p", { class: "figure-num num" }, money(forecast.monthEndExpected)),
		h("p", { class: "figure-label" }, "Likely total spend by the end of this month"),
		rangeChart(forecast.monthEndLow, forecast.monthEndExpected, forecast.monthEndHigh, width),
		h("p", { class: "sr-only" }, "Between " + money(forecast.monthEndLow) + " and " + money(forecast.monthEndHigh) + "."),
	);
}

function categoryBlock(data) {
	const rows = data.byCategory || [];
	if (rows.length === 0) {
		return block("Where it went", false, empty());
	}
	const children = [barList(rows)];
	const nw = data.needsWants;
	if (nw && Number(nw.needs) + Number(nw.wants) > 0) {
		const wants = Number(nw.wantsPercent) || 0;
		children.push(
			h(
				"div",
				{ class: "split" },
				h(
					"div",
					{ class: "split-labels" },
					h("span", null, "Needs ", h("strong", { class: "num" }, money(nw.needs)), " (" + percentText(100 - wants) + ")"),
					h("span", null, "Wants ", h("strong", { class: "num" }, money(nw.wants)), " (" + percentText(wants) + ")"),
				),
				splitBar((100 - wants) / 100),
			),
		);
	}
	return block("Where it went", false, ...children);
}

function modeBlock(data) {
	const rows = data.byMode || [];
	return block("How you paid", false, rows.length ? barList(rows) : empty());
}

function dailyBlock(data, width) {
	const days = data.daily || [];
	const any = days.some((d) => Number(d.amount) > 0);
	if (!any) {
		return block("Day by day", true, empty());
	}
	const holder = h("div", { class: "daily-holder" }, dailyChart(days, width));
	const note = data.period && data.period.code === "ALL_TIME" && days.length >= 90 ? h("p", { class: "sub" }, "The last 90 days.") : null;
	return block("Day by day", true, holder, note);
}

function itemsBlock(data) {
	const rows = data.topItems || [];
	if (rows.length === 0) {
		return block("What you buy most", false, empty());
	}
	const list = h("ul", { class: "items" });
	for (const item of rows) {
		list.append(
			h(
				"li",
				null,
				h("span", { class: "item-name" }, item.item, h("span", { class: "sub" }, item.categoryLabel || "")),
				h("span", { class: "item-count num" }, item.count === 1 ? "1 time" : item.count + " times"),
				h("span", { class: "item-total num" }, money(item.amount)),
			),
		);
	}
	return block("What you buy most", false, list);
}

const SEVERITY = {
	ALERT: { icon: "alert", word: "Important" },
	WARN: { icon: "alert", word: "Watch" },
	INFO: { icon: "info", word: "Note" },
	GOOD: { icon: "good", word: "Good" },
};

function insightsBlock(data) {
	const rows = data.insights || [];
	if (rows.length === 0) {
		return block("What we noticed", false, empty());
	}
	const list = h("ul", { class: "notes" });
	for (const insight of rows) {
		const kind = SEVERITY[insight.severity] || SEVERITY.INFO;
		list.append(
			h(
				"li",
				{ class: insight.severity === "INFO" ? "quiet" : null },
				icon(kind.icon),
				h("div", null, h("strong", null, h("span", { class: "sr-only" }, kind.word + ": "), insight.title), h("p", null, insight.text)),
			),
		);
	}
	return block("What we noticed", false, list);
}

function tipsBlock(data) {
	const rows = data.tips || [];
	if (rows.length === 0) {
		return block("Tips to save", true, empty());
	}
	const list = h("ul", { class: "notes" });
	for (const tip of rows) {
		list.append(
			h(
				"li",
				null,
				icon("bulb"),
				h("div", null, h("p", null, tip.text)),
			),
		);
	}
	return block("Tips to save", true, list);
}

function qualityNote(data) {
	const quality = data.dataQuality;
	if (!quality) {
		return null;
	}
	const count = Number(quality.needsReviewCount) || 0;
	const text = quality.message || (count > 0 ? (count === 1 ? "1 spend needs a category check." : count + " spends need a category check.") : null);
	if (!text) {
		return null;
	}
	return h(
		"div",
		{ class: "quality block wide" },
		h("span", null, text),
		count > 0 ? h("button", { type: "button", class: "btn link", on: { click: showNeedsChecking } }, count === 1 ? "Show it" : "Show them") : null,
	);
}

// ----- drawing and loading ---------------------------------------------------

function innerWidth(columns) {
	const body = $("analysis-body");
	const total = body.clientWidth || 320;
	const wide = window.matchMedia("(min-width: 768px)").matches;
	const pad = wide ? 46 : 30;
	if (columns === 2 || !wide) {
		return total - pad;
	}
	return (total - 20) / 2 - pad;
}

function draw() {
	const body = $("analysis-body");
	const data = state.data;
	clear(body);
	if (!data) {
		return;
	}
	const nothing =
		!(Number((data.totals || {}).count) > 0) &&
		!data.health &&
		!data.runway &&
		!data.forecast &&
		!(data.insights || []).length &&
		!(data.tips || []).length;
	if (nothing) {
		const quality = data.dataQuality || {};
		body.append(
			block(
				(data.period && data.period.label) || "Analysis",
				true,
				h("p", { class: "summary-text" }, data.summary || quality.message || "No spends here yet. " + NOT_YET),
			),
		);
		return;
	}
	body.append(
		summaryBlock(data),
		healthBlock(data.health),
		h("div", { class: "block-stack" }, runwayBlock(data.runway), forecastBlock(data.forecast, innerWidth(1))),
		categoryBlock(data),
		modeBlock(data),
		dailyBlock(data, innerWidth(2)),
		itemsBlock(data),
		insightsBlock(data),
		tipsBlock(data),
	);
	const note = qualityNote(data);
	if (note) {
		body.append(note);
	}
}

async function load() {
	state.loadNo += 1;
	const mine = state.loadNo;
	if (state.abort) {
		state.abort.abort();
	}
	state.abort = new AbortController();
	const signal = state.abort.signal;
	const body = $("analysis-body");
	body.setAttribute("aria-busy", "true");
	try {
		const wanted = api.get("/api/analysis?period=" + state.period, { signal });
		const month = state.period === "THIS_MONTH" ? wanted : api.get("/api/analysis?period=THIS_MONTH", { signal });
		const [data, monthData] = await Promise.all([wanted, month]);
		if (mine !== state.loadNo) {
			return;
		}
		state.data = data;
		paintSpent(monthData.totals);
		draw();
	} catch (error) {
		if ((error && error.name === "AbortError") || mine !== state.loadNo) {
			return;
		}
		state.data = null;
		clear(body);
		body.append(
			h(
				"div",
				{ class: "panel block wide" },
				h("p", { class: "empty" }, "Cannot load the analysis. " + errorText(error)),
				h("p", null, h("button", { type: "button", class: "btn secondary", on: { click: load } }, "Try again")),
			),
		);
	} finally {
		if (mine === state.loadNo) {
			body.setAttribute("aria-busy", "false");
		}
	}
}

export function setupAnalysis() {
	const saved = recall("period");
	if (PERIODS.includes(saved)) {
		state.period = saved;
	}
	const radios = Array.from(document.querySelectorAll('input[name="period"]'));
	for (const radio of radios) {
		radio.checked = radio.value === state.period;
		radio.addEventListener("change", () => {
			if (radio.checked) {
				state.period = radio.value;
				remember("period", state.period);
				load();
			}
		});
	}
	on("changed", load);

	// charts are drawn at the real width: draw again when it changes
	let lastWidth = 0;
	let resizeTimer = null;
	window.addEventListener("resize", () => {
		window.clearTimeout(resizeTimer);
		resizeTimer = window.setTimeout(() => {
			const width = $("analysis-body").clientWidth;
			if (width !== lastWidth) {
				lastWidth = width;
				draw();
			}
		}, 150);
	});

	return load();
}
