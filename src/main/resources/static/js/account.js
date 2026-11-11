// The account menu in the top bar: change password, download data, delete account, log out.

import { api, errorText } from "/js/api.js";
import { h, icon, passwordField, passwordRules, formNotice, openDialog, submitting, setFieldError, setNotice, toast } from "/js/ui.js";
import { store } from "/js/store.js";

const $ = (id) => document.getElementById(id);

// ----- menu ------------------------------------------------------------------

function setupMenu() {
	const button = $("account-btn");
	const menu = $("account-menu");
	const open = (show) => {
		menu.hidden = !show;
		button.setAttribute("aria-expanded", String(show));
	};
	button.append(icon("chevronDown"));
	button.addEventListener("click", () => {
		open(menu.hidden);
		if (!menu.hidden) {
			menu.querySelector("button").focus();
		}
	});
	document.addEventListener("click", (event) => {
		if (!menu.hidden && !$("account").contains(event.target)) {
			open(false);
		}
	});
	$("account").addEventListener("keydown", (event) => {
		if (event.key === "Escape" && !menu.hidden) {
			open(false);
			button.focus();
		}
		if ((event.key === "ArrowDown" || event.key === "ArrowUp") && !menu.hidden) {
			const items = Array.from(menu.querySelectorAll("button"));
			const at = items.indexOf(document.activeElement);
			const next = event.key === "ArrowDown" ? (at + 1) % items.length : (at - 1 + items.length) % items.length;
			items[next].focus();
			event.preventDefault();
		}
	});
	$("account").addEventListener("focusout", (event) => {
		if (!menu.hidden && event.relatedTarget && !$("account").contains(event.relatedTarget)) {
			open(false);
		}
	});
	const pick = (id, action) => {
		$(id).addEventListener("click", () => {
			open(false);
			button.focus();
			action();
		});
	};
	pick("menu-password", changePassword);
	pick("menu-export", downloadData);
	pick("menu-delete", deleteAccount);
	pick("menu-logout", logOut);
}

export function paintUser(user) {
	const name = (user && user.name) || "";
	$("account-initial").textContent = (name.trim()[0] || "?").toUpperCase();
	$("account-name").textContent = name;
	$("menu-name").textContent = name;
	$("menu-email").textContent = (user && user.email) || "";
	$("menu-phone").textContent = (user && user.phoneMasked) || "";
	$("account").hidden = false;
}

// ----- actions ---------------------------------------------------------------

function changePassword() {
	openDialog("Change password", (body, close) => {
		const current = passwordField({ label: "Current password", name: "currentPassword", autocomplete: "current-password" });
		const fresh = passwordField({ label: "New password", name: "newPassword", autocomplete: "new-password" });
		const rules = passwordRules(fresh.input, () => ({ email: store.user ? store.user.email : "", phone: "" }));
		fresh.wrap.append(rules.list);
		// lets a password manager tie the new password to the right account
		const who = h("input", { type: "text", name: "username", autocomplete: "username", class: "sr-only", tabindex: "-1", "aria-hidden": "true", readonly: true });
		who.value = store.user ? store.user.email : "";
		const form = h(
			"form",
			{ novalidate: true },
			formNotice(),
			who,
			current.wrap,
			fresh.wrap,
			h(
				"div",
				{ class: "dialog-actions" },
				h("button", { type: "button", class: "btn secondary", on: { click: close } }, "Cancel"),
				h("button", { type: "submit", class: "btn" }, "Change password"),
			),
		);
		form.addEventListener("submit", (event) => {
			event.preventDefault();
			submitting(
				form,
				async () => {
					if (current.input.value === "") {
						setFieldError(current.input, "Enter your current password.");
						current.input.focus();
						return false;
					}
					if (fresh.input.value === "") {
						setFieldError(fresh.input, "Enter a new password.");
						fresh.input.focus();
						return false;
					}
					await api.post("/api/account/password", { currentPassword: current.input.value, newPassword: fresh.input.value });
					close();
					toast("Password changed.");
					return true;
				},
				{ currentPassword: current.input, newPassword: fresh.input },
			);
		});
		body.append(form);
	});
}

/** Saves the user's data as an Excel file (the JSON backup stays at /api/export). */
async function fetchExport() {
	const response = await api.get("/api/export/excel", { raw: true });
	const blob = await response.blob();
	const header = response.headers.get("Content-Disposition") || "";
	const match = /filename="?([^";]+)"?/i.exec(header);
	const name = match ? match[1] : "spend-analyzer.xlsx";
	const url = URL.createObjectURL(blob);
	const link = h("a", { href: url, download: name, hidden: true });
	document.body.append(link);
	link.click();
	link.remove();
	window.setTimeout(() => URL.revokeObjectURL(url), 30000);
}

async function downloadData() {
	const item = $("menu-export");
	if (item.disabled) {
		return;
	}
	item.disabled = true;
	toast("Getting your data...");
	try {
		await fetchExport();
		toast("Your data is saved as an Excel file.");
	} catch (error) {
		toast("Cannot download. " + errorText(error));
	} finally {
		item.disabled = false;
	}
}

function deleteAccount() {
	openDialog("Delete my account", (body, close) => {
		const password = passwordField({ label: "Your password", name: "password", autocomplete: "current-password" });
		const notice = formNotice();
		const exportButton = h("button", { type: "button", class: "btn link" }, "Download my data first");
		exportButton.addEventListener("click", async () => {
			exportButton.disabled = true;
			try {
				await fetchExport();
				toast("Your data is saved as an Excel file.");
			} catch (error) {
				setNotice(notice, "Cannot download. " + errorText(error), "problem");
			} finally {
				exportButton.disabled = false;
			}
		});
		const who = h("input", { type: "text", name: "username", autocomplete: "username", class: "sr-only", tabindex: "-1", "aria-hidden": "true", readonly: true });
		who.value = store.user ? store.user.email : "";
		const form = h(
			"form",
			{ novalidate: true },
			h("p", null, "This deletes your account, all your spends and your balances. It cannot be undone."),
			h("p", null, exportButton),
			notice,
			who,
			password.wrap,
			h(
				"div",
				{ class: "dialog-actions" },
				h("button", { type: "button", class: "btn secondary", on: { click: close } }, "Keep my account"),
				h("button", { type: "submit", class: "btn strong" }, "Delete for ever"),
			),
		);
		form.addEventListener("submit", (event) => {
			event.preventDefault();
			submitting(
				form,
				async () => {
					if (password.input.value === "") {
						setFieldError(password.input, "Enter your password.");
						password.input.focus();
						return false;
					}
					await api.del("/api/account", { password: password.input.value });
					window.location.replace("/login.html#deleted");
					return await new Promise(() => {});
				},
				{ password: password.input },
			);
		});
		body.append(form);
	});
}

async function logOut() {
	const item = $("menu-logout");
	item.disabled = true;
	try {
		await api.post("/api/auth/logout", {});
	} catch (error) {
		// even if the call failed, leave the page: the login page checks the session again
	}
	window.location.replace("/login.html#out");
}

export function setupAccount() {
	setupMenu();
}
