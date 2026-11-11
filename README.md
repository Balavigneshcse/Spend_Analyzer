# Spend Analyzer

A personal spend tracker. You enter your bank balance, cash in hand and credit card due, then log each spend in your own words ("juse", "briyany", "pen", "bus ticket"). A hand-written AI recognises what it was, even when misspelt, puts it in a category, and gives you an analysis of where your money goes.

- **Frontend:** plain HTML, CSS and JavaScript (no framework), served by the backend.
- **Backend:** Java 25, Spring Boot 4, Spring Security, plain JDBC.
- **Database:** PostgreSQL with Flyway migrations.
- **AI:** written from scratch in Java inside this repository (`com.spendanalyzer.ai` and `com.spendanalyzer.insight`). No AI service and no machine-learning library is used.

## What it does

- Register with email, Indian mobile number and a strong password. A 6-digit code is emailed to finish registration and to reset a password.
- One dashboard: balances, add a spend, transactions, analysis.
- Payment modes: UPI and debit card reduce the bank balance, cash reduces cash in hand, credit card adds to the card due.
- Add money, withdraw cash from an ATM, pay the credit card bill, correct a balance.
- Borrow money from a friend (name, amount, by UPI or in cash) and pay it back later. The dashboard shows what you owe each friend.
- The AI picks the category; if you correct it, it remembers for next time.
- Analysis: spending by category and payment mode, items you buy most often, daily trend, unusual spends, how many days your money will last, a health score, and a short written summary with tips.

## Run it on your computer

You need Java 25 and PostgreSQL running on `localhost:5432` with user `postgres` / password `postgres`.

1. Create the database once (any SQL tool): `CREATE DATABASE spend_analyzer;`
2. Start the app:
   - Windows: `.\mvnw.cmd spring-boot:run`
   - macOS / Linux: `./mvnw spring-boot:run`
3. Open <http://localhost:8080>.

On your computer the OTP code is **printed in the terminal** where the app runs (no email is sent).

Different database settings: set the environment variables `DB_NAME`, `DB_USER`, `DB_PASSWORD`, or a full `DATABASE_URL` (`postgresql://user:password@host:5432/dbname`).

### Tests

Create a second database once: `CREATE DATABASE spend_analyzer_test;` then run `.\mvnw.cmd verify` (Windows) or `./mvnw verify`. The test database is wiped on every run.

## Deploy to Render (free)

The app runs on Render, the data lives on Neon, and OTP emails go through Brevo. All three have free plans.

Why not Render's own free database and Gmail directly? Render deletes its free PostgreSQL after 30 days, and Render's free plan blocks normal email (SMTP) ports.

### 1. Neon (database)

1. Sign up at <https://neon.com> and create a project. Choose region **AWS Asia Pacific (Singapore)**.
2. On the project dashboard press **Connect**, switch **Connection pooling off**, and copy the connection string. It looks like
   `postgresql://neondb_owner:xxxx@ep-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require`

### 2. Brevo (OTP email)

1. Sign up at <https://www.brevo.com> (free plan, 300 emails a day).
2. **Settings → Senders, domains, IPs → Senders → Add a sender.** Use your own email address and verify it with the code Brevo sends you.
3. **Settings → SMTP & API → API keys → Generate a new API key.** Copy it now; it is shown only once.
4. **Settings → Security → Authorized IPs:** make sure blocking of unknown IP addresses is **off** for API keys (Render's addresses change).

### 3. Render (the app)

1. Sign up at <https://render.com> with your GitHub account.
2. **New → Blueprint**, pick this repository. Render reads `render.yaml`.
3. Render asks for three values:
   - `DATABASE_URL`: the Neon connection string from step 1
   - `BREVO_API_KEY`: the key from step 2
   - `MAIL_FROM_EMAIL`: the sender address you verified in Brevo
4. Press **Apply**. The first build takes several minutes. When it is live, open the `https://....onrender.com` address.

Every push to `main` deploys again automatically.

### After your first login

- Register your own account on the live site. If nobody else should be able to sign up, open the service in the Render dashboard, go to **Environment**, and add the variable `APP_REGISTRATION_ENABLED` with the value `false`.
- If the code email does not arrive, look in the **Spam** folder. Emails sent through Brevo from a Gmail address are sometimes filtered at first.

### Things to know about the free plan

- The app sleeps after 15 minutes without visitors. The next visit takes about one to two minutes to wake it up.
- Never put keys or passwords in this repository. They belong only in the Render dashboard.

## Settings

| Environment variable | Needed in production | Meaning |
|---|---|---|
| `DATABASE_URL` | yes | `postgresql://user:password@host/db?sslmode=require` |
| `BREVO_API_KEY` | yes | Brevo API key for OTP emails |
| `MAIL_FROM_EMAIL` | yes | Verified sender address |
| `MAIL_FROM_NAME` | no | Sender name, default "Spend Analyzer" |
| `OTP_PEPPER` | yes | Random secret of at least 32 characters (Render generates it) |
| `APP_REGISTRATION_ENABLED` | no | `false` closes registration |
| `APP_ORIGIN` | no | Public address of the site; on Render it is detected automatically |
| `PORT` | no | Port to listen on (Render sets it) |

## Project layout

```
src/main/java/com/spendanalyzer
  ai/        the spend classifier: text clean-up, word list, spelling/sound matching, self-trained model
  insight/   the analysis engine: statistics, findings, written summary
  auth/      registration, OTP, login, sessions, password reset
  ledger/    balances and entries
  config/ security/ common/ model/ web/   shared plumbing
src/main/resources
  db/migration/   Flyway SQL
  ai/             the AI's word lists
  static/         the web pages
docs/API.md       the HTTP API
```
