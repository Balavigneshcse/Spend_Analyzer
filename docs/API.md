# Spend Analyzer API

This is the contract between the browser pages and the Spring Boot backend.

## Rules for every call

- Base path `/api`. Bodies and responses are JSON (`Content-Type: application/json`).
- **Money is a string** with two decimals: `"1234.50"`. Never a JSON number. Input accepts `"120"`, `"49.5"`, `"49.50"`; no sign, no commas, no exponent.
- **Dates** are `"YYYY-MM-DD"` in the user's calendar (India). Timestamps are ISO-8601 instants.
- Login state is a session cookie set by the server (`HttpOnly`, `SameSite=Strict`). The browser JS never sees it; `fetch` must use `credentials: "same-origin"`.
- Every state-changing call must be sent as JSON from the same origin (the server rejects cross-site requests with 403 `cross_site` and non-JSON bodies with 415 `json_required`).
- Unknown JSON properties are rejected with 400.

### Errors

```json
{ "error": "validation", "message": "Enter an amount above zero.", "fields": { "amount": "Enter an amount above zero." }, "retryAfterSeconds": 42 }
```

`fields` and `retryAfterSeconds` appear only when relevant. `message` is safe to show to the user.

| Status | `error` | Meaning |
|---|---|---|
| 400 | `validation` | A field is wrong; see `fields` |
| 400 | `bad_request` | Body could not be read |
| 400 | `invalid_code` | OTP wrong or expired |
| 401 | `unauthorized` | Not logged in: go to the login page |
| 401 | `invalid_credentials` | Wrong email or password |
| 403 | `cross_site`, `forbidden`, `registration_closed` | Not allowed |
| 404 | `not_found` | No such thing (also used for other users' data) |
| 409 | `already_onboarded`, `conflict` | State conflict |
| 405 / 406 / 415 | `request_error` | Wrong method or media type |
| 413 | `too_large` | Body larger than 16 KB |
| 415 | `json_required` | Body was not JSON |
| 429 | `too_many_requests` | Slow down; `Retry-After` header and `retryAfterSeconds` say when |
| 500 | `server_error` | Unexpected; message carries a reference |

---

## Auth (`/api/auth/**`, public)

Input rules (server is authoritative, the page shows the same rules live):

- **name**: 2 to 60 characters.
- **email**: valid address, at most 254 characters; stored lower-case.
- **phone**: Indian mobile. Accepts `9876543210`, `09876543210`, `919876543210`, `+91 98765 43210`. Stored as `+919876543210`. First digit 6 to 9.
- **password**: 10 to 64 characters, at least one lower-case letter, one upper-case letter, one digit and one symbol; must not contain the email name part or the phone number; must not be a well-known password.
- **code**: exactly 6 digits. Valid 10 minutes, 5 wrong tries per code.

### `GET /api/auth/config`
`200 { "registrationEnabled": true }`

### `POST /api/auth/register`
Body `{ "name", "email", "phone", "password" }`

`200 { "registrationId": "uuid", "expiresInSeconds": 600, "resendAfterSeconds": 60 }`

Always this answer for a well-formed request, whether or not the email already has an account (an existing owner gets a notice email instead of a code). No account exists until the code is verified. 403 `registration_closed` when registration is switched off.

### `POST /api/auth/register/verify`
Body `{ "registrationId", "code" }`

`200 { "user": User }` and the session cookie is set (the user is logged in). 400 `invalid_code` otherwise.

### `POST /api/auth/register/resend`
Body `{ "registrationId" }` → `200 { "expiresInSeconds": 600, "resendAfterSeconds": 60 }`; 429 when asked too soon or too often.

### `POST /api/auth/login`
Body `{ "email", "password" }` → `200 { "user": User }` + cookie. 401 `invalid_credentials` (same answer for unknown email and wrong password). 429 after repeated failures.

### `POST /api/auth/logout`
`204`. Deletes the session and clears the cookie. Safe to call when not logged in.

### `GET /api/auth/me`
`200 { "user": User }` or 401 `unauthorized`.

```json
User = { "name": "Bala", "email": "bala@example.com", "phoneMasked": "+91 ••••••3210" }
```

### `POST /api/auth/password-reset/request`
Body `{ "email" }` → `200 { "resetId": "uuid", "expiresInSeconds": 600, "resendAfterSeconds": 60 }` always (no hint whether the email exists).

### `POST /api/auth/password-reset/resend`
Body `{ "resetId" }` → `200 { "expiresInSeconds": 600, "resendAfterSeconds": 60 }`.

### `POST /api/auth/password-reset/confirm`
Body `{ "resetId", "code", "newPassword" }` → `204`. All sessions of the user are deleted; the user must log in with the new password. 400 `invalid_code` or `validation` (weak password).

## Account (login required)

### `POST /api/account/password`
Body `{ "currentPassword", "newPassword" }` → `204`. Other sessions are logged out. 400 `validation` with `fields.currentPassword` when the current password is wrong.

### `DELETE /api/account`
Body `{ "password" }` → `204`. Deletes the user and all their data, clears the cookie.

---

## Fixed lists (login required)

### `GET /api/meta`
```json
{
  "categories": [ { "code": "FOOD_MEALS", "label": "Food & Meals", "need": "NEED" } ],
  "paymentModes": [ { "code": "UPI", "label": "UPI", "account": "BANK" } ],
  "accounts": [ { "code": "BANK", "label": "Bank account" } ]
}
```
Categories: `FOOD_MEALS, SNACKS_BEVERAGES, GROCERIES, TRANSPORT_FUEL, EDUCATION_STATIONERY, RENT_UTILITIES, MOBILE_INTERNET, HEALTH_MEDICINE, PERSONAL_CARE, SHOPPING, ENTERTAINMENT_SUBS, SOCIAL_GIFTS, TRANSFERS_FEES, OTHER`.
Payment modes: `UPI` and `DEBIT_CARD` (bank), `CREDIT_CARD` (card due), `CASH` (cash in hand).
Accounts: `BANK`, `CASH`, `CREDIT_CARD`.

---

## Balances and entries (login required)

Three balances per user. `bank` and `cash` are money you have. `creditCardDue` is money you owe on the card. Balances may go negative (the page shows a warning) because the entered balance can be out of date.

```json
Balances = { "bank": "5200.00", "cash": "340.00", "creditCardDue": "0.00", "available": "5540.00", "owedToFriends": "1500.00", "onboarded": true }
```
`available` = bank + cash. `owedToFriends` = everything borrowed from friends and not yet paid back (never negative).

### `GET /api/balances`
`200 Balances`. `onboarded` is false until the user has entered opening balances.

### `PUT /api/balances/opening`
Body `{ "bank": "5200", "cash": "340", "creditCardDue": "0" }` (each 0 or more; `creditCardDue` optional, default 0).
Sets the starting balances and marks the user onboarded. `200 Balances`. 409 `already_onboarded` if done before (use an ADJUSTMENT entry afterwards).

### Entry

```json
Entry = {
  "id": "uuid",
  "type": "EXPENSE",            // EXPENSE | INCOME | WITHDRAWAL | CARD_PAYMENT | ADJUSTMENT | BORROW | REPAY
  "amount": "40.00",            // positive; for ADJUSTMENT the signed difference, e.g. "-120.00"
  "account": "BANK",            // balance it touched (see below)
  "person": null,               // BORROW and REPAY only: the friend's name as typed, e.g. "Ravi"
  "paymentMode": "UPI",         // EXPENSE only, else null
  "description": "juse",        // what the user typed; null allowed for non-expenses
  "category": "SNACKS_BEVERAGES", // EXPENSE only, else null
  "categoryLabel": "Snacks & Beverages",
  "item": "juice",              // what the AI recognised; EXPENSE only
  "aiConfidence": 0.88,         // 0..1, EXPENSE only
  "aiSource": "LEXICON_FUZZY",
  "needsReview": false,         // AI unsure: show "please check"
  "userCorrected": false,       // user chose the category
  "date": "2026-11-10",
  "createdAt": "2026-11-10T08:15:30Z"
}
```

What each type does:

| Type | Body fields (besides `clientId`, `type`, `date`) | Effect |
|---|---|---|
| `EXPENSE` | `description` (1-120), `amount`, `paymentMode`, optional `category` | UPI/DEBIT_CARD: bank down. CASH: cash down. CREDIT_CARD: card due up |
| `INCOME` | `amount`, `account` (`BANK` or `CASH`), optional `description` | That balance up |
| `WITHDRAWAL` | `amount` | Bank down, cash up (ATM) |
| `CARD_PAYMENT` | `amount`, `account` (`BANK` or `CASH`) | That balance down, card due down |
| `ADJUSTMENT` | `account` (any of the three), `newBalance` (0 or more) | Sets that balance to `newBalance`; the entry stores the difference. 400 if nothing changes |
| `BORROW` | `person` (1-40), `amount`, `account` (`BANK` = received by UPI, `CASH` = received in cash), optional `description` | That balance up; you now owe that friend the amount |
| `REPAY` | `person`, `amount`, `account` (`BANK` = paid by UPI, `CASH` = paid in cash), optional `description` | That balance down; what you owe that friend goes down |

Borrowing from friends:

- `person` is the friend's name: 1 to 40 characters after trimming, no control characters. "Ravi", "ravi" and " RAVI " are the same friend (compared without case, extra spaces, invisible characters and Unicode spelling differences); the first spelling used is the one shown.
- Borrowed money is not income and paying it back is not spending: neither appears in the analysis totals.
- `REPAY` is refused with 400 `validation` (`fields.amount`) when it is more than you owe that friend, and with `fields.person` when you owe that friend nothing.
- Deleting a `BORROW` that has already been (partly) paid back, so that the friend would end up below zero, is refused with 409 `conflict` and a message telling the user to delete the pay-back first.
- `BORROW` and `REPAY` entries cannot be edited (`PATCH` is for expenses); delete and re-enter instead.

### `GET /api/debts`
What the user owes, per friend. Friends who are fully paid back are not listed.
```json
{
  "total": "1500.00",
  "people": [
    { "name": "Ravi", "owed": "1000.00", "borrowed": "1500.00", "repaid": "500.00", "lastDate": "2026-11-08" },
    { "name": "Arun", "owed": "500.00", "borrowed": "500.00", "repaid": "0.00", "lastDate": "2026-11-02" }
  ],
  "knownNames": [ "Arun", "Ravi", "Suresh" ]
}
```
`people` is sorted by `owed`, largest first. `knownNames` lists the friends that appear in the user's entries (for suggestions while typing), sorted by name, at most 200; a name disappears once every entry with that friend is deleted. The name shown for a friend is the spelling of their oldest entry.

### `POST /api/entries/preview`
Live AI guess while typing. Body `{ "description": "juse", "amount": "40", "paymentMode": "UPI" }` (`amount`, `paymentMode` optional). Saves nothing.
```json
{
  "category": "SNACKS_BEVERAGES", "categoryLabel": "Snacks & Beverages",
  "confidence": 0.88, "item": "juice",
  "explanation": "\"juse\" sounds like \"juice\"",
  "needsReview": false, "source": "LEXICON_FUZZY",
  "alternatives": [ { "category": "FOOD_MEALS", "categoryLabel": "Food & Meals", "confidence": 0.07 } ]
}
```

### `POST /api/entries`
Body: `clientId` (a UUID the page generates once per form submit), `type`, `date` (not more than 1 day in the future, not before 2000-01-01; default today), plus the fields of the type.

- For `EXPENSE` without `category`, the AI chooses. With `category`, the user's choice wins; it is remembered for next time.
- `201 { "entry": Entry, "balances": Balances }`. Sending the same `clientId` again returns `200` with the original entry (no double charge).

### `GET /api/entries`
Query (all optional): `from`, `to` (dates, inclusive), `type`, `mode`, `category`, `needsReview=true`, `q` (text in description or friend's name), `page` (0-based, default 0), `size` (default 20, max 100).
Newest first (date, then creation time).
`200 { "items": [Entry], "page": 0, "size": 20, "total": 57, "hasMore": true }`

### `PATCH /api/entries/{id}`
EXPENSE only. Body with any of `description`, `amount`, `paymentMode`, `date`, `category`. Balances are corrected for the difference.
Giving `category` records the user's choice and teaches the AI. Changing only `description` re-runs the AI unless the user chose the category earlier.
`200 { "entry": Entry, "balances": Balances }`. 404 for unknown ids or other users' entries. 400 for non-expense entries.

### `DELETE /api/entries/{id}`
Any type. Reverses its effect on the balances. `200 { "balances": Balances }`. 404 if not found.

### `GET /api/export`
All of the user's data as a JSON file download (`Content-Disposition: attachment`): `{ "exportedAt", "user": User, "balances": Balances, "debts": Debts, "entries": [Entry] }` (`Debts` is the `GET /api/debts` answer).
This is the full backup. At most the newest 20,000 entries.

### `GET /api/export/excel`
The same data as an Excel workbook; this is what "Download my data" on the page saves.
`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `Content-Disposition: attachment; filename="spend-analyzer-YYYY-MM-DD.xlsx"` (the date in India), `Cache-Control: no-store`.
Read in one snapshot like the JSON export, and capped the same way: the newest 20,000 entries.

| Sheet | What is in it |
|---|---|
| `Summary` | Exported on (date and time in India), Name, Email, Phone (masked like `+91 ••••••3210`), Bank balance, Cash in hand, Credit card due, Total money you have (bank + cash), Owed to friends, Entries in this file. When the cap cut the list, one more line: "Only your newest 20,000 entries are included." |
| `Transactions` | One row per entry, newest first. Columns: Date, Type, What, Category, Paid by / Account, Amount, Friend, Note, Needs checking, Added on. |
| `Friends you owe` | Friend, Owed, Borrowed, Paid back, Last date, then an empty row and a `Total` row. When nothing is owed, the single line "You do not owe any friend money." |

- **Type** uses the page's words: Spend, Money added, Cash withdrawn, Card bill paid, Balance corrected, Opening balance, Borrowed, Paid back.
- **What** is the description; for money with a friend it is "Borrowed from Ravi" / "Paid back Ravi" and the description goes to **Note**. **Category** is filled for spends only. **Paid by / Account** is UPI, Debit card, Credit card or Cash for a spend; To bank / To cash, Bank to cash, From bank / From cash, the balance name for a correction, UPI or Cash for money with a friend.
- **Amount** is signed like the transaction list: money out (a spend, a withdrawal, a card bill, a pay-back) is negative, money in (money added, a borrow) is positive, a balance correction keeps its own sign.
- Money cells are numbers (Excel can add them up) shown as `"₹"#,##0.00`, negatives with a minus sign. Dates are real Excel dates (`d mmm yyyy`), "Added on" and "Exported on" date and time.
- Every text cell is plain text (an inline string), never a formula: a description such as `=HYPERLINK(...)`, `+1+1` or `@SUM(A1)` shows as typed and is never run. Characters XML cannot hold are dropped; a cell holds at most 32,767 characters.
- Text that looks like Excel's character code `_xHHHH_` (for example `bill_x0041_ref`) is stored with the underscore written as `_x005F_`, so Excel shows it as typed.
- On `Transactions` and `Friends you owe` the header row stays on screen while scrolling and has filter buttons. On `Friends you owe` the filter stops at the last friend (the empty row keeps Excel from growing it), so sorting keeps `Total` at the bottom; the "nobody owed" sentence is a plain sheet with no filter.
- An error before the file starts (for example the account was deleted) is the usual JSON error. An error after it started leaves the file without its end, so the download fails instead of saving a shorter workbook.

---

## Analysis (login required)

### `GET /api/analysis?period=THIS_MONTH`
`period`: `THIS_WEEK` (Monday to today), `THIS_MONTH` (default), `LAST_MONTH`, `LAST_30_DAYS`, `ALL_TIME`.

Only `EXPENSE` entries count as spending. Blocks that need more data than the user has are `null` (the page shows a friendly "add more spends" state); lists are empty arrays, never null.

```json
{
  "period": { "code": "THIS_MONTH", "label": "This month", "from": "2026-11-01", "to": "2026-11-10", "days": 10 },
  "totals": {
    "spent": "4520.00", "count": 38, "avgPerDay": "452.00",
    "biggestDay": { "date": "2026-11-03", "amount": "900.00" },
    "biggestSpend": { "description": "shoes", "amount": "800.00", "date": "2026-11-03", "category": "SHOPPING", "categoryLabel": "Shopping" },
    "previousSpent": "3900.00", "changePercent": 15.9
  },
  "byCategory": [ { "category": "FOOD_MEALS", "label": "Food & Meals", "need": "NEED", "amount": "1500.00", "count": 12, "percent": 33.2 } ],
  "byMode": [ { "mode": "UPI", "label": "UPI", "amount": "2700.00", "count": 20, "percent": 59.7 } ],
  "daily": [ { "date": "2026-11-01", "amount": "320.00" } ],
  "topItems": [ { "item": "juice", "count": 14, "amount": "560.00", "category": "SNACKS_BEVERAGES", "categoryLabel": "Snacks & Beverages" } ],
  "needsWants": { "needs": "3000.00", "wants": "1500.00", "wantsPercent": 33.3 },
  "runway": { "available": "2150.00", "dailyBurn": "165.00", "daysLeft": 13, "safePerDay": "107.50", "daysToMonthEnd": 20 },
  "forecast": { "monthEndExpected": "9800.00", "monthEndLow": "9100.00", "monthEndHigh": "10500.00" },
  "health": {
    "score": 72, "band": "Good", "provisional": false,
    "components": [ { "name": "Runway", "points": 22, "max": 30, "note": "Money lasts about 13 days" } ]
  },
  "insights": [ { "id": "leak-juice", "type": "SMALL_LEAK", "severity": "WARN", "title": "Juice adds up", "text": "You bought juice 14 times this month: ₹560 in total." } ],
  "tips": [ { "text": "Cap juice at 3 a week: saves about ₹320 a month.", "saving": "320.00" } ],
  "summary": "You spent ₹4,520 on 38 purchases this month, about ₹452 a day. ...",
  "dataQuality": { "needsReviewCount": 3, "enoughData": true, "message": null },
  "generatedAt": "2026-11-10T08:15:30Z"
}
```

- `totals.avgPerDay` is spent divided by the days of the period since the user's first entry (so a user who started mid-month is not averaged over days they did not track).
- `totals.biggestDay`, `totals.biggestSpend`, `totals.previousSpent`, `totals.changePercent` may be `null`. `tips[].saving` may be `null`.
- `byCategory`, `byMode`, `topItems` are sorted by amount, largest first. `percent` values are of the period's total spend, one decimal.
- `daily` has one row for every day of the period (zero days included), oldest first; for `ALL_TIME` only the most recent 90 days.
- `runway`, `forecast` and `health` describe the user's situation today (they use recent history and current balances, not the chosen period). Each may be `null` when there is too little data.
- `insights[].severity` is `ALERT`, `WARN`, `INFO` or `GOOD`. `insights[].type` is one of `SMALL_LEAK, UNUSUAL_SPEND, PERIOD_CHANGE, RUNWAY, FORECAST, RECURRING, WEEK_PATTERN, NEEDS_WANTS, TOP_CATEGORY, REVIEW_NEEDED, CREDIT_CARD, BORROWED, POSITIVE`. At most 6 insights and 3 tips, most important first.
- `BORROWED` appears when the user owes friends money: it states the total, names the friends owed the most, and says how much bank + cash would be left after paying everyone back. It is about today, like `RUNWAY`.
- `summary` is plain text (no HTML), 2 to 5 sentences. With very little data it is a short friendly note.
- `dataQuality.message` is a short hint such as "Add 5 more spends to unlock trends", or `null`.
- All text is plain text. The page must render it with `textContent`, never as HTML.
