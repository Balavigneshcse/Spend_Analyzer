/**
 * Spend Analyzer mail relay - a Google Apps Script web app.
 *
 * What it does: the Spend Analyzer server sends a POST request with JSON to this web app.
 * The script checks a shared secret and then sends ONE email from your own Gmail account.
 *
 * How to set it up (the README of the project has every step):
 *   1. Paste this whole file into a new project at https://script.google.com
 *   2. Deploy -> New deployment -> Web app -> Execute as: Me, Who has access: Anyone
 *   3. Project Settings -> Script Properties -> add a property named SECRET.
 *      Its value is GOOGLE_SCRIPT_SECRET from Render (32 or more characters).
 *
 * NEVER write the secret into this file. It belongs only in Script Properties.
 *
 * If you change this code later: Deploy -> Manage deployments -> Edit -> Version: New version
 * -> Deploy. The web app URL stays the same.
 */

// Limits for one request. The server stays far below them.
const MIN_SECRET = 32;        // characters; a shorter SECRET is treated as "no secret"
const MAX_REQUEST = 100000;   // characters of the whole request
const MAX_SUBJECT = 200;      // characters
const MAX_TEXT = 10000;       // characters
const MAX_HTML = 50000;       // characters
const MAX_NAME = 60;          // characters
const DEFAULT_NAME = 'Spend Analyzer';

// Opening the web app URL in a browser only shows "ok". It never sends an email.
function doGet() {
  return ContentService.createTextOutput('ok');
}

// The server calls this. It is the only place that sends an email.
function doPost(e) {
  try {
    // 1. Without a good SECRET in Script Properties nobody may send an email.
    const saved = savedSecret();
    if (saved.length < MIN_SECRET) return reply(false, 'not_configured');

    // 2. Read the JSON of the request.
    const raw = e && e.postData && e.postData.contents;
    if (typeof raw !== 'string' || raw.length > MAX_REQUEST) return reply(false, 'bad_json');
    let data;
    try {
      data = JSON.parse(raw);
    } catch (err) {
      return reply(false, 'bad_json');
    }
    if (data === null || typeof data !== 'object' || Array.isArray(data)) return reply(false, 'bad_json');

    // 3. Check the secret. It is never written to the log and never sent back.
    if (typeof data.secret !== 'string' || !sameText(data.secret, saved)) return reply(false, 'unauthorized');

    // 4. Check the fields: exactly one address, sensible lengths, no line break in the subject.
    const to = data.to;
    if (typeof to !== 'string' || to.length > 254 ||
        !/^[^\s@,;<>"()\\]+@[^\s@,;<>"()\\]+\.[^\s@,;<>"()\\]{2,}$/.test(to)) return reply(false, 'bad_to');

    const subject = data.subject;
    if (typeof subject !== 'string' || subject.length < 1 || subject.length > MAX_SUBJECT ||
        /[\r\n]/.test(subject)) return reply(false, 'bad_subject');

    const text = data.text;
    if (typeof text !== 'string' || text.length < 1 || text.length > MAX_TEXT) return reply(false, 'bad_text');

    const html = data.html;
    const hasHtml = html !== undefined && html !== null && html !== '';
    if (hasHtml && (typeof html !== 'string' || html.length > MAX_HTML)) return reply(false, 'bad_html');

    // The sender name people see next to your Gmail address.
    let name = typeof data.fromName === 'string' ? data.fromName : '';
    name = name.replace(/[\r\n"<>]/g, '').trim().slice(0, MAX_NAME) || DEFAULT_NAME;

    // 5. Stop here when today's sending limit of your Gmail account is used up.
    if (MailApp.getRemainingDailyQuota() < 1) return reply(false, 'quota');

    // 6. Send one email from your Gmail address.
    const options = { name: name };
    if (hasHtml) options.htmlBody = html;
    MailApp.sendEmail(to, subject, text, options);

    return reply(true);
  } catch (err) {
    // Something went wrong. The reason is written to the "Executions" page of this project when
    // Google shows it there (it does not always). The server only gets a short error word.
    // To look for the reason yourself, run checkSetup from the editor.
    const message = String((err && err.message) || err);
    console.error('Mail relay error: ' + message);
    const isQuota = /too many times|quota|limit exceeded/i.test(message);
    return reply(false, isQuota ? 'quota' : 'send_failed');
  }
}

// Run this from the editor: choose "checkSetup" at the top and press Run. It sends NO email.
// The first time it asks for your permission. Then it shows if the setup is complete.
function checkSetup() {
  console.log('SECRET is saved and long enough: ' + (savedSecret().length >= MIN_SECRET));
  console.log('Emails you can still send today: ' + MailApp.getRemainingDailyQuota());
}

// The secret from Script Properties, or '' when there is none.
function savedSecret() {
  return (PropertiesService.getScriptProperties().getProperty('SECRET') || '').trim();
}

// Compare two texts without stopping at the first different letter.
function sameText(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// Always answer with a small JSON: {"ok":true} or {"ok":false,"error":"word"}.
function reply(ok, error) {
  const body = ok ? { ok: true } : { ok: false, error: error };
  return ContentService.createTextOutput(JSON.stringify(body))
      .setMimeType(ContentService.MimeType.JSON);
}
