# Spend Analyzer

**Live site:** <https://spend-analyzer-gyms.onrender.com> (the first visit after a quiet time takes about one to two minutes to wake up)

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

The app runs on Render, the data lives on Neon, and the code emails are sent from **your own Gmail** by a small Google Apps Script. All three are free. You need a GitHub account and a Gmail account, nothing else.

Why this way? Render deletes its own free PostgreSQL after 30 days, so the database is on Neon. Render's free plan also blocks the normal email (SMTP) ports, so the app cannot talk to Gmail directly. The Google script is reached over HTTPS, which Render allows.

Do the parts in this order.

### 1. Neon (database)

1. Sign up at <https://neon.com> and create a project. Choose region **AWS Asia Pacific (Singapore)**.
2. On the project dashboard press **Connect**, switch **Connection pooling off**, and copy the connection string. It looks like
   `postgresql://neondb_owner:xxxx@ep-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require`

### 2. Google Apps Script (code emails from your Gmail)

1. Open <https://script.google.com> and sign in with the Gmail account that should send the code emails. Use a normal `@gmail.com` account, not a school or company account.
2. Click **New project**. Click the name **Untitled project** at the top, type `Spend Analyzer Mail` and click **Rename**.
3. You see a file `Code.gs` with a few lines in it. Delete all of those lines. Open [`deploy/google-apps-script/Code.gs`](deploy/google-apps-script/Code.gs) in this repository, copy everything, and paste it into the editor. Press **Ctrl+S** to save.
4. At the top, in the small list next to **Debug**, choose `checkSetup`. Click **Run**. This sends no email.
5. A box says that permission is needed. Click **Review permissions** and choose your Gmail account.
6. You see "Google hasn't verified this app". This is normal, because it is your own script. Click **Advanced**, then **Go to Spend Analyzer Mail (unsafe)**.
7. Google asks to let the script send email as you. If you see check boxes, tick all of them. Click **Allow** (or **Continue**).
   The **Execution log** at the bottom now says `SECRET is saved and long enough: false`. That is correct for now; you add the secret in part 4.
   If you see "This app is blocked" and there is no way to continue, this Google account cannot be used. Use [Brevo](#alternative-brevo) instead.
8. Click the blue **Deploy** button (top right), then **New deployment**.
9. Next to **Select type** click the gear icon and choose **Web app**.
10. In **Description** type `mail relay`. In **Execute as** choose **Me**. In **Who has access** choose **Anyone**.
11. Click **Deploy**. If it asks to **Authorize access**, click it and do steps 5 to 7 again.
12. Under **Web app** you see a URL that ends with `/exec`. Click **Copy** and keep it. Click **Done**.
13. Test it: open a private (incognito) browser window, paste the URL and press Enter. You must see only the word `ok`. This test sends no email.
    If you see a Google sign-in page instead, **Who has access** is not **Anyone**. Go to **Deploy → Manage deployments**, click the pencil icon, set it to **Anyone** and click **Deploy**.

"Anyone" only means that the web address works without a Google login. The script sends an email only when the caller knows a secret. You add that secret in part 4; until then the script sends nothing.

### 3. Render (the app)

1. Sign up at <https://render.com> with your GitHub account.
2. **New → Blueprint**, pick this repository. Render reads `render.yaml`.
3. Render asks for two values:
   - `DATABASE_URL`: the Neon connection string from part 1
   - `GOOGLE_SCRIPT_URL`: the URL ending with `/exec` from part 2
4. Press **Apply**. The first build takes several minutes. When it is live, open the `https://....onrender.com` address.

Every push to `main` deploys again automatically.

**Is your site already on Render from before (set up with Brevo)?** Then Render does not ask for the new value. Open your service, click **Environment**, add `GOOGLE_SCRIPT_URL` yourself with the URL from part 2, and save. Until you do, the new version does not start and the Render log says `(GOOGLE_SCRIPT_URL) is not set`. Then continue with part 4.

### 4. Give the script its secret

Render has made a long random secret for you. The script needs the same one.

1. In Render open your service and click **Environment**. Find `GOOGLE_SCRIPT_SECRET`, show its value (eye icon) and copy it.
2. Go back to your script at <https://script.google.com>. On the left side click the gear icon **Project Settings**. Scroll down to **Script Properties** and click **Add script property**.
3. In **Property** type `SECRET` (all capital letters). In **Value** paste the secret. Click **Save script properties**.
4. On the left side click **Editor** (the `< >` icon) and run `checkSetup` again. The **Execution log** at the bottom must say `SECRET is saved and long enough: true`. It also shows how many emails you can still send today (about 100).

You do not have to deploy the script again after this.

### 5. Try it

Open your site and register with another email address that you own. The code email should arrive within about one minute.

Then, if nobody else should be able to sign up, open the service in the Render dashboard, go to **Environment**, and add the variable `APP_REGISTRATION_ENABLED` with the value `false`. This also stops strangers from using up your daily emails.

### If the code email does not arrive

1. Look in the **Spam** folder.
2. In the script editor click **Executions** on the left side. A code email normally shows as one line with the function `doPost` and the status **Completed**, even when the email was not sent. Click the line: if it shows a message that starts with `Mail relay error`, that is the reason. Google does not always show messages for calls made without a login, so the Render log in the next step is the place to trust.
3. In Render open **Logs** and look for a line with `Could not send` or `Mail circuit breaker is open`. The line says what went wrong:

| The log line contains | What it means and what to do |
|---|---|
| `unauthorized` | The `SECRET` in the script and `GOOGLE_SCRIPT_SECRET` in Render are not the same. Copy it again (part 4). |
| `not_configured` | The script has no `SECRET` property yet. Do part 4. |
| `quota` | Your Gmail has sent all its emails for today. It works again within 24 hours. |
| `HTTP 404` | `GOOGLE_SCRIPT_URL` is wrong, or the deployment was deleted. Copy the URL again from **Deploy → Manage deployments** and put it in Render. |
| `not_json` or `accounts.google.com` | **Who has access** is not **Anyone**, or the script lost its permission. Check the deployment (part 2, step 13) and run `checkSetup` again. |
| `send_failed` | Gmail refused the email. Run `checkSetup` in the script editor: if it asks for permission again, or shows 0 emails left, that is the reason. The **Executions** page may also show a `Mail relay error` message. |
| `Mail circuit breaker is open` | The app has reached its own limit of emails for 24 hours (see below). |

### Things to know

- **The emails come from your own Gmail address.** Everyone who gets a code sees that address, and replies come to your inbox. You will normally find every code email in the **Sent** folder of that Gmail. Open the first one you receive and check that it did not go to Spam.
- **Daily limit.** Google lets a normal Gmail account send 100 emails in 24 hours through a script. The app stops by itself at 80, to stay on the safe side. If more people than that ask for a code in one day, the others get no email until the next day. `checkSetup` shows how many emails are left.
- **Keep the secret secret.** It belongs in two places only: **Environment** in Render and **Script Properties** in the script. Never write it into `Code.gs` and never put it in GitHub. Anyone who has it can send email as you. If it ever leaks, put a new long random value (at least 32 characters) in both places.
- **If you change `Code.gs` later:** paste the new code, save, then **Deploy → Manage deployments →** pencil icon **→ Version: New version → Deploy**. The URL stays the same. Do not use **New deployment** again, because that makes a different URL.
- **If you remove the script's access** in your Google account (Security → third-party connections), or delete the script, the emails stop. Run `checkSetup` to give the permission again.
- The app sleeps after 15 minutes without visitors. The next visit takes about one to two minutes to wake it up, and the first code email after that is slower too.
- Never put keys or passwords in this repository. They belong only in the Render dashboard.

### Alternative: Brevo

Use this if you do not want to send from your Gmail through a script, or if Google blocked the script. Brevo is an email service with a free plan (300 emails a day). Emails sent through Brevo from a Gmail address are sometimes put in Spam.

1. Sign up at <https://www.brevo.com>.
2. **Settings → Senders, domains, IPs → Senders → Add a sender.** Use your own email address and verify it with the code Brevo sends you.
3. **Settings → SMTP & API → API keys → Generate a new API key.** Copy it now; it is shown only once.
4. **Settings → Security → Authorized IPs:** make sure blocking of unknown IP addresses is **off** for API keys (Render's addresses change).
5. In `render.yaml` change the value of `MAIL_PROVIDER` from `google` to `brevo`, then commit and push. (Change it in the file, so that Render does not change it back later.)
6. If you have no service on Render yet, do part 3 now (type `none` for `GOOGLE_SCRIPT_URL`). The first deploy fails and the Render log says `(BREVO_API_KEY) is not set`. That is expected.
7. In Render open your service, click **Environment** and add two variables: `BREVO_API_KEY` (the key from step 3) and `MAIL_FROM_EMAIL` (the sender address from step 2). Save. Saving starts a new deploy, and this one works. If no deploy starts, click **Manual Deploy → Deploy latest commit**.

With Brevo, parts 2 and 4 above are not needed.

## Settings

| Environment variable | Needed in production | Meaning |
|---|---|---|
| `DATABASE_URL` | yes | `postgresql://user:password@host/db?sslmode=require` |
| `MAIL_PROVIDER` | no | Who sends the code emails: `google` (default) or `brevo` |
| `GOOGLE_SCRIPT_URL` | yes, with `google` | Web app URL of your Google Apps Script; it ends with `/exec` |
| `GOOGLE_SCRIPT_SECRET` | yes, with `google` | The same secret as the script property `SECRET`, at least 32 characters (Render generates it) |
| `BREVO_API_KEY` | yes, with `brevo` | Brevo API key for OTP emails |
| `MAIL_FROM_EMAIL` | yes, with `brevo` | Sender address verified in Brevo |
| `MAIL_FROM_NAME` | no | Sender name, default "Spend Analyzer" |
| `OTP_PEPPER` | yes | Random secret of at least 32 characters (Render generates it) |
| `APP_AUTH_LIMITS_MAILSPERDAY` | no | Most emails the app sends in 24 hours. Default 80 with `google`, 200 with `brevo` |
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
