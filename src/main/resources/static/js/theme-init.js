// Light or dark: puts the theme on <html> before the first paint, so the wrong one never flashes.
// A plain script loaded in <head> before the style sheets (inline code is forbidden by the
// Content-Security-Policy). First visit: the device setting. After the button is pressed: that
// choice, kept on this device as "light" or "dark" under "sa.theme" - nothing else is stored.
(function () {
	var KEY = "sa.theme";
	// the browser's own bar on a phone gets the top bar colour (--brand in css/base.css)
	var BAR = { light: "#5b2d8e", dark: "#38205c" };
	var root = document.documentElement;
	var device = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
	var chosen = null; // the choice, also when storage is blocked

	function saved() {
		try {
			var value = window.localStorage.getItem(KEY);
			return value === "light" || value === "dark" ? value : null;
		} catch (ignored) {
			return null;
		}
	}

	function setMeta(name, content) {
		var meta = document.querySelector('meta[name="' + name + '"]');
		if (meta) {
			meta.setAttribute("content", content);
		}
	}

	function apply() {
		var theme = chosen || (device && device.matches ? "dark" : "light");
		root.setAttribute("data-theme", theme);
		setMeta("theme-color", BAR[theme]);
		setMeta("color-scheme", theme);
		document.dispatchEvent(new Event("sa-theme"));
	}

	chosen = saved();
	apply();

	// never chosen: keep following the device while the page is open
	if (device && device.addEventListener) {
		device.addEventListener("change", apply);
	}
	// paper is white: print in the light theme, then go back to the theme that was on
	window.addEventListener("beforeprint", function () {
		root.setAttribute("data-theme", "light");
	});
	window.addEventListener("afterprint", apply);
	// chosen in another tab: follow it here too
	window.addEventListener("storage", function (event) {
		if (event.key === KEY || event.key === null) {
			chosen = saved();
			apply();
		}
	});

	window.saTheme = {
		get: function () {
			return root.getAttribute("data-theme");
		},
		set: function (theme) {
			chosen = theme === "dark" ? "dark" : "light";
			try {
				window.localStorage.setItem(KEY, chosen);
			} catch (ignored) {
				// storage is blocked: the choice lasts until the page is closed
			}
			apply();
		},
	};
})();
