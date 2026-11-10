package com.spendanalyzer.auth;

/** The "User" shape of docs/API.md: name, email and the masked phone number. Nothing else leaves the server. */
public record UserView(String name, String email, String phoneMasked) {
}
