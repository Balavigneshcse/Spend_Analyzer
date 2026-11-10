// Hand-drawn SVG charts in one hue. Every bar carries its own text label, so colour never
// has to explain anything. Colours come from CSS classes (style attributes are not allowed).

import { s, h, money, moneyShort, showDate } from "/js/ui.js";

const clamp = (value, low, high) => Math.min(high, Math.max(low, value));

/** A thin track with a filled part. fraction 0..1, opacity for rank. */
export function meter(fraction, opacity, className) {
	const part = clamp(Number(fraction) || 0, 0, 1);
	const svg = s("svg", { class: className || "bar", "aria-hidden": "true", focusable: "false" });
	svg.append(s("rect", { class: "fill-track", x: 0, y: 0, width: "100%", height: "100%", rx: 4 }));
	if (part > 0) {
		svg.append(s("rect", { class: "fill-main", x: 0, y: 0, width: Math.max(part * 100, 1.2) + "%", height: "100%", rx: 4, "fill-opacity": opacity === undefined ? 1 : opacity }));
	}
	return svg;
}

/**
 * Sorted horizontal bars: label, amount, percent of the total, and a bar.
 * rows: [{ label, amount, percent, count }]
 */
export function barList(rows) {
	const list = h("ul", { class: "bars" });
	rows.forEach((item, index) => {
		const opacity = clamp(1 - index * 0.13, 0.35, 1);
		const percent = Number(item.percent) || 0;
		list.append(
			h(
				"li",
				null,
				h(
					"div",
					{ class: "bar-top" },
					h("span", { class: "bar-label" }, item.label),
					h("span", { class: "bar-val num" }, money(item.amount)),
					h("span", { class: "bar-pct num" }, percent.toLocaleString("en-IN", { maximumFractionDigits: 1 }) + "%"),
				),
				meter(percent / 100, opacity),
			),
		);
	});
	return list;
}

/** Two parts of one whole on a single bar, each labelled in words. */
export function splitBar(leftFraction) {
	const part = clamp(Number(leftFraction) || 0, 0, 1) * 100;
	const svg = s("svg", { class: "bar", "aria-hidden": "true", focusable: "false" });
	svg.append(s("rect", { class: "fill-soft", x: 0, y: 0, width: "100%", height: "100%", rx: 4 }));
	if (part > 0) {
		svg.append(s("rect", { class: "fill-main", x: 0, y: 0, width: part + "%", height: "100%", rx: 4 }));
	}
	return svg;
}

/**
 * Daily spending as columns. days: [{ date, amount }], oldest first.
 * Draws at the real pixel width so the text stays sharp; call again when the width changes.
 */
export function dailyChart(days, width) {
	const W = Math.max(260, Math.floor(width));
	const H = W < 520 ? 190 : 220;
	const left = 4;
	const right = 4;
	const top = 26;
	const bottom = 24;
	const plotW = W - left - right;
	const plotH = H - top - bottom;
	const values = days.map((d) => Number(d.amount) || 0);
	const max = Math.max(...values, 0);
	const svg = s("svg", { class: "daily", viewBox: "0 0 " + W + " " + H, width: W, height: H, role: "img" });

	let peak = 0;
	values.forEach((v, i) => {
		if (v > values[peak]) {
			peak = i;
		}
	});
	const spentDays = values.filter((v) => v > 0).length;
	svg.setAttribute(
		"aria-label",
		"Daily spending from " + showDate(days[0].date) + " to " + showDate(days[days.length - 1].date) + ". " + spentDays + " days with spending. Highest: " + money(values[peak]) + " on " + showDate(days[peak].date) + ".",
	);

	const n = days.length;
	const slot = plotW / n;
	const barW = clamp(slot * 0.68, 1.5, 28);
	const baseY = top + plotH;

	// top guide line with the largest value
	svg.append(s("line", { class: "stroke-dash", x1: left, x2: W - right, y1: top, y2: top }));
	svg.append(s("line", { class: "stroke-line", x1: left, x2: W - right, y1: baseY + 0.5, y2: baseY + 0.5 }));

	values.forEach((value, i) => {
		const x = left + slot * i + (slot - barW) / 2;
		const day = days[i];
		if (value <= 0) {
			const dot = s("rect", { class: "fill-zero", x, y: baseY - 2, width: barW, height: 2, rx: 1 });
			dot.append(s("title", null, showDate(day.date) + ": no spending"));
			svg.append(dot);
			return;
		}
		const height = Math.max(2, (value / max) * plotH);
		const bar = s("rect", { class: "fill-main", x, y: baseY - height, width: barW, height, rx: Math.min(2, barW / 2), "fill-opacity": i === peak ? 1 : 0.62 });
		bar.append(s("title", null, showDate(day.date) + ": " + money(value)));
		svg.append(bar);
	});

	// the highest day is labelled right on the chart
	const peakX = clamp(left + slot * peak + slot / 2, left + 44, W - right - 44);
	svg.append(s("text", { class: "chart-text strong", x: peakX, y: top - 9, "text-anchor": "middle" }, moneyShort(values[peak]) + " on " + showDate(days[peak].date)));

	// date labels: first, last and a few between, as many as fit
	const room = Math.max(2, Math.floor(plotW / 64));
	const step = Math.max(1, Math.ceil((n - 1) / (room - 1)));
	const marks = [];
	for (let i = 0; i < n; i += step) {
		marks.push(i);
	}
	if (marks[marks.length - 1] !== n - 1) {
		if (n - 1 - marks[marks.length - 1] < step * 0.6) {
			marks.pop();
		}
		marks.push(n - 1);
	}
	marks.forEach((i) => {
		const x = left + slot * i + slot / 2;
		const anchor = i === 0 ? "start" : i === n - 1 ? "end" : "middle";
		const tx = i === 0 ? left : i === n - 1 ? W - right : x;
		svg.append(s("text", { class: "chart-text", x: tx, y: H - 6, "text-anchor": anchor }, showDate(days[i].date)));
	});
	return svg;
}

/** Low - expected - high on one line, with the three values written under it. */
export function rangeChart(low, expected, high, width) {
	const W = Math.max(220, Math.floor(width));
	const H = 34;
	const pad = 6;
	const lo = Number(low);
	const hi = Number(high);
	const mid = Number(expected);
	const span = hi - lo;
	const at = (v) => (span <= 0 ? W / 2 : pad + ((v - lo) / span) * (W - pad * 2));
	const svg = s("svg", { class: "range", viewBox: "0 0 " + W + " " + H, width: W, height: H, "aria-hidden": "true", focusable: "false" });
	svg.append(s("rect", { class: "fill-soft", x: pad, y: 8, width: W - pad * 2, height: 6, rx: 3 }));
	svg.append(s("circle", { class: "fill-main", cx: at(mid), cy: 11, r: 6 }));
	svg.append(s("text", { class: "chart-text", x: pad, y: 30, "text-anchor": "start" }, "Low " + moneyShort(lo)));
	svg.append(s("text", { class: "chart-text", x: W - pad, y: 30, "text-anchor": "end" }, "High " + moneyShort(hi)));
	return svg;
}
