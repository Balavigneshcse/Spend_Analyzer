// Signed-out screens: log in, create account, enter code, forgot password.

import { api, ApiError, errorText, waitWords } from "/js/api.js";
import { passwordRules, revealButton, setFieldError, setNotice, submitting, announce } from "/js/ui.js";

const $ = (id) => document.getElementById(id);

const TITLES = {
	login: "Log in",
	register: "Create account",
	code: "Enter code",
	forgot: "Forgot password",
	reset: "Set a new password",
};

const state = {
	registrationId: null,
	registerEmail: "",
	resetId: null,
	resetEmail: "",
	registrationOpen: true,
};

// ----- switching views ------------------------------------------------------

function show(view, options = {}) {
	if (view === "register" && !state.registrationOpen) {
		view = "login";
	}
	if (view === "code" && !state.registrationId) {
		view = "register";
	}
	if (view === "reset" && !state.resetId) {
		view = "forgot";
	}
	for (const section of document.querySelectorAll("[data-view]")) {
		section.hidden = section.dataset.view !== view;
	}
	document.title = TITLES[view] + " - Spend Analyzer";
	const section = document.querySelector('[data-view="' + view + '"]');
	if (!options.keepMessages) {
		const notice = section.querySelector(".form-notice");
		setNotice(notice, "");
	}
	if (options.focus !== false) {
		const target = section.querySelector("input");
		if (target && !options.focusHeading) {
			target.focus();
		} else {
			section.querySelector("h1").focus();
		}
	}
	window.scrollTo(0, 0);
}

for (const button of document.querySelectorAll("[data-go]")) {
	button.addEventListener("click", () => show(button.dataset.go));
}

// password Show / Hide buttons
for (const box of document.querySelectorAll(".reveal")) {
	box.append(revealButton(box.querySelector("input")));
}

// ----- countdown for "Send again" -------------------------------------------

function countdown(button) {
	let timer = null;
	let left = 0;
	const paint = () => {
		if (left > 0) {
			button.disabled = true;
			button.textContent = "Send again in " + left + " s";
		} else {
			button.disabled = false;
			button.textContent = "Send again";
		}
	};
	return {
		start(seconds) {
			window.clearInterval(timer);
			left = Math.max(0, Math.ceil(seconds));
			paint();
			if (left > 0) {
				timer = window.setInterval(() => {
					left -= 1;
					paint();
					if (left <= 0) {
						window.clearInterval(timer);
					}
				}, 1000);
			}
		},
		get running() {
			return left > 0;
		},
	};
}

const codeTimer = countdown($("code-resend"));
const resetTimer = countdown($("reset-resend"));

function digitsOnly(input) {
	input.addEventListener("input", () => {
		const clean = input.value.replace(/\D/g, "").slice(0, 6);
		if (clean !== input.value) {
			input.value = clean;
		}
	});
}

digitsOnly($("code-input"));
digitsOnly($("reset-code"));

function readCode(input) {
	const code = input.value.trim();
	if (!/^\d{6}$/.test(code)) {
		setFieldError(input, "Enter the 6 digits from the email.");
		input.focus();
		return null;
	}
	return code;
}

/** Messages for a code that was refused. */
function codeProblem(form, input, error) {
	if (error instanceof ApiError && error.code === "invalid_code") {
		setFieldError(input, "That code is wrong or has expired. Check the email, or press Send again for a new code.");
		input.select();
		input.focus();
		announce("That code is wrong or has expired.");
		return true;
	}
	if (error instanceof ApiError && error.status === 429) {
		setNotice(form.querySelector(".form-notice"), "Too many wrong codes. " + (error.retryAfterSeconds ? "Try again in " + waitWords(error.retryAfterSeconds) + "." : "Please try again later."), "problem");
		announce("Too many wrong codes.");
		return true;
	}
	return false;
}

function requireText(input, message) {
	if (input.value.trim() === "") {
		setFieldError(input, message);
		return false;
	}
	return true;
}

function focusFirstInvalid(form) {
	const bad = form.querySelector('[aria-invalid="true"]');
	if (bad) {
		bad.focus();
	}
}

// ----- log in ---------------------------------------------------------------

const loginForm = $("form-login");
loginForm.addEventListener("submit", (event) => {
	event.preventDefault();
	const email = $("login-email");
	const password = $("login-password");
	submitting(
		loginForm,
		async () => {
			const ok = [requireText(email, "Enter your email."), password.value === "" ? (setFieldError(password, "Enter your password."), false) : true];
			if (ok.includes(false)) {
				focusFirstInvalid(loginForm);
				return false;
			}
			await api.post("/api/auth/login", { email: email.value.trim(), password: password.value });
			window.location.replace("/");
			return await new Promise(() => {});
		},
		{ email, password },
	);
});

// ----- create account -------------------------------------------------------

const registerForm = $("form-register");
const regInputs = { name: $("reg-name"), email: $("reg-email"), phone: $("reg-phone"), password: $("reg-password") };
const regRules = passwordRules(regInputs.password, () => ({ email: regInputs.email.value, phone: regInputs.phone.value }));
$("reg-rules").append(regRules.list);
regInputs.email.addEventListener("input", regRules.update);
regInputs.phone.addEventListener("input", regRules.update);

registerForm.addEventListener("submit", (event) => {
	event.preventDefault();
	submitting(
		registerForm,
		async () => {
			const ok = [
				requireText(regInputs.name, "Enter your name."),
				requireText(regInputs.email, "Enter your email."),
				requireText(regInputs.phone, "Enter your mobile number."),
				regInputs.password.value === "" ? (setFieldError(regInputs.password, "Enter a password."), false) : true,
			];
			if (ok.includes(false)) {
				focusFirstInvalid(registerForm);
				return false;
			}
			const email = regInputs.email.value.trim();
			const answer = await api.post("/api/auth/register", {
				name: regInputs.name.value.trim(),
				email,
				phone: regInputs.phone.value.trim(),
				password: regInputs.password.value,
			});
			state.registrationId = answer.registrationId;
			state.registerEmail = email;
			$("code-email").textContent = email;
			$("code-input").value = "";
			codeTimer.start(answer.resendAfterSeconds || 60);
			show("code");
			announce("Enter the code from your email.");
			return true;
		},
		regInputs,
	);
});

// ----- enter code (new account) ---------------------------------------------

const codeForm = $("form-code");
codeForm.addEventListener("submit", (event) => {
	event.preventDefault();
	const input = $("code-input");
	submitting(
		codeForm,
		async () => {
			const code = readCode(input);
			if (code === null) {
				return false;
			}
			try {
				await api.post("/api/auth/register/verify", { registrationId: state.registrationId, code });
			} catch (error) {
				if (codeProblem(codeForm, input, error)) {
					return false;
				}
				throw error;
			}
			window.location.replace("/");
			return await new Promise(() => {});
		},
		{ code: input },
	);
});

async function resend(form, button, timer, path, body) {
	if (timer.running || form.dataset.busy === "1") {
		return;
	}
	const notice = form.querySelector(".form-notice");
	setNotice(notice, "");
	button.disabled = true;
	try {
		const answer = await api.post(path, body);
		timer.start(answer.resendAfterSeconds || 60);
		setNotice(notice, "If this email can receive a code, we have sent a new one. Old codes no longer work.", "good");
		announce("A new code is on its way.");
	} catch (error) {
		button.disabled = false;
		if (error instanceof ApiError && error.status === 429) {
			setNotice(notice, errorText(error), "problem");
			if (error.retryAfterSeconds && error.retryAfterSeconds <= 600) {
				timer.start(error.retryAfterSeconds);
			}
		} else if (error instanceof ApiError && error.code === "invalid_code") {
			setNotice(notice, "This took too long. Please start again.", "problem");
		} else {
			setNotice(notice, errorText(error), "problem");
		}
		announce(notice.textContent);
	}
}

$("code-resend").addEventListener("click", () => {
	resend(codeForm, $("code-resend"), codeTimer, "/api/auth/register/resend", { registrationId: state.registrationId });
});

// ----- forgot password ------------------------------------------------------

const forgotForm = $("form-forgot");
forgotForm.addEventListener("submit", (event) => {
	event.preventDefault();
	const email = $("forgot-email");
	submitting(
		forgotForm,
		async () => {
			if (!requireText(email, "Enter your email.")) {
				email.focus();
				return false;
			}
			const address = email.value.trim();
			const answer = await api.post("/api/auth/password-reset/request", { email: address });
			state.resetId = answer.resetId;
			state.resetEmail = address;
			$("reset-email").textContent = address;
			$("reset-code").value = "";
			$("reset-password").value = "";
			resetRules.update();
			resetTimer.start(answer.resendAfterSeconds || 60);
			show("reset");
			return true;
		},
		{ email },
	);
});

const resetForm = $("form-reset");
const resetRules = passwordRules($("reset-password"), () => ({ email: state.resetEmail, phone: "" }));
$("reset-rules").append(resetRules.list);

resetForm.addEventListener("submit", (event) => {
	event.preventDefault();
	const code = $("reset-code");
	const password = $("reset-password");
	submitting(
		resetForm,
		async () => {
			const value = readCode(code);
			if (value === null) {
				return false;
			}
			if (password.value === "") {
				setFieldError(password, "Enter a new password.");
				password.focus();
				return false;
			}
			try {
				await api.post("/api/auth/password-reset/confirm", { resetId: state.resetId, code: value, newPassword: password.value });
			} catch (error) {
				if (codeProblem(resetForm, code, error)) {
					return false;
				}
				throw error;
			}
			state.resetId = null;
			code.value = "";
			password.value = "";
			$("login-email").value = state.resetEmail;
			$("login-password").value = "";
			show("login", { keepMessages: true });
			setNotice(loginForm.querySelector(".form-notice"), "Password changed. Log in with your new password.", "good");
			$("login-password").focus();
			announce("Password changed. Log in with your new password.");
			return true;
		},
		{ code, newPassword: password },
	);
});

$("reset-resend").addEventListener("click", () => {
	resend(resetForm, $("reset-resend"), resetTimer, "/api/auth/password-reset/resend", { resetId: state.resetId });
});

// ----- start ----------------------------------------------------------------

function startNote() {
	const notice = loginForm.querySelector(".form-notice");
	const hash = window.location.hash;
	if (hash === "#deleted") {
		setNotice(notice, "Your account and all its data are deleted.", "good");
	} else if (hash === "#out") {
		setNotice(notice, "You are logged out.", "good");
	} else if (hash === "#password") {
		setNotice(notice, "Password changed. Please log in again.", "good");
	}
	if (hash) {
		window.history.replaceState(null, "", window.location.pathname);
	}
}

async function start() {
	startNote();
	api.get("/api/auth/config")
		.then((config) => {
			state.registrationOpen = config.registrationEnabled !== false;
			for (const link of document.querySelectorAll("[data-register-link]")) {
				link.hidden = !state.registrationOpen;
			}
			if (!state.registrationOpen && !$("view-register").hidden) {
				show("login");
			}
		})
		.catch(() => {});
	try {
		await api.get("/api/auth/me");
		window.location.replace("/");
	} catch (error) {
		// not logged in: stay here
	}
}

start();
