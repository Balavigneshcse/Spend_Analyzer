// The light / dark button in the top bar of both pages.
// /js/theme-init.js (loaded in <head>) has already set the theme and owns the saved choice;
// this only draws the button and asks it to switch.

import { icon, announce } from "/js/ui.js";

const root = document.documentElement;
const button = document.getElementById("theme-toggle");
const theme = window.saTheme;

function paint() {
	const dark = theme.get() === "dark";
	const label = dark ? "Switch to light mode" : "Switch to dark mode";
	// the picture shows what you get: a moon while it is light, a sun while it is dark
	button.replaceChildren(icon(dark ? "sun" : "moon"));
	button.setAttribute("aria-label", label);
	button.title = label;
}

if (button && theme) {
	let timer = null;
	button.addEventListener("click", () => {
		const next = theme.get() === "dark" ? "light" : "dark";
		// a short colour fade for this change only (the style sheet skips it for reduced motion)
		root.classList.add("theme-switching");
		window.clearTimeout(timer);
		timer = window.setTimeout(() => root.classList.remove("theme-switching"), 260);
		theme.set(next);
		announce(next === "dark" ? "Dark mode is on." : "Light mode is on.");
	});
	document.addEventListener("sa-theme", paint);
	paint();
} else if (button) {
	button.hidden = true;
}
