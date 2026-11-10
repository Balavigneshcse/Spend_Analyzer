-- Spend Analyzer: base schema.
-- Money is NUMERIC(12,2) everywhere. Every user-owned table cascades from users.

-- ---------------------------------------------------------------- reference
CREATE TABLE categories (
    code       VARCHAR(32) PRIMARY KEY,
    label      VARCHAR(60) NOT NULL,
    need_flag  VARCHAR(10) NOT NULL CHECK (need_flag IN ('NEED', 'WANT', 'EXCLUDED', 'UNKNOWN')),
    sort_order SMALLINT    NOT NULL
);

INSERT INTO categories (code, label, need_flag, sort_order) VALUES
    ('FOOD_MEALS',           'Food & Meals',                  'NEED',     1),
    ('SNACKS_BEVERAGES',     'Snacks & Beverages',            'WANT',     2),
    ('GROCERIES',            'Groceries & Essentials',        'NEED',     3),
    ('TRANSPORT_FUEL',       'Transport & Fuel',              'NEED',     4),
    ('EDUCATION_STATIONERY', 'Education & Stationery',        'NEED',     5),
    ('RENT_UTILITIES',       'Rent & Utilities',              'NEED',     6),
    ('MOBILE_INTERNET',      'Mobile & Internet',             'NEED',     7),
    ('HEALTH_MEDICINE',      'Health & Medicine',             'NEED',     8),
    ('PERSONAL_CARE',        'Personal Care',                 'NEED',     9),
    ('SHOPPING',             'Shopping',                      'WANT',     10),
    ('ENTERTAINMENT_SUBS',   'Entertainment & Subscriptions', 'WANT',     11),
    ('SOCIAL_GIFTS',         'Gifts, Treats & Donations',     'WANT',     12),
    ('TRANSFERS_FEES',       'Transfers, Lending & Fees',     'EXCLUDED', 13),
    ('OTHER',                'Other',                         'UNKNOWN',  14);

-- ---------------------------------------------------------------- users & auth
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    name          VARCHAR(60)  NOT NULL,
    phone         VARCHAR(13)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    onboarded     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_email_uk UNIQUE (email),
    CONSTRAINT users_email_lower_ck CHECK (email = lower(email)),
    CONSTRAINT users_phone_ck CHECK (phone ~ '^\+91[6-9][0-9]{9}$')
);

-- A registration that has not proved its email yet. No users row exists until the OTP is verified.
CREATE TABLE pending_registrations (
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    name          VARCHAR(60)  NOT NULL,
    phone         VARCHAR(13)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX pending_registrations_email_idx ON pending_registrations (email);

-- One row per emailed code. flow_id is the id the browser holds (registration id or reset id).
CREATE TABLE otp_challenges (
    id          UUID PRIMARY KEY,
    flow_id     UUID         NOT NULL,
    email       VARCHAR(254) NOT NULL,
    purpose     VARCHAR(20)  NOT NULL CHECK (purpose IN ('REGISTER', 'PASSWORD_RESET')),
    user_id     UUID         REFERENCES users (id) ON DELETE CASCADE,
    code_hmac   BYTEA        NOT NULL,
    attempts    SMALLINT     NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX otp_challenges_flow_idx ON otp_challenges (flow_id, created_at DESC);
CREATE INDEX otp_challenges_email_idx ON otp_challenges (email, purpose, created_at DESC);

-- Login sessions. Only the SHA-256 of the cookie token is stored.
CREATE TABLE sessions (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   BYTEA       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT sessions_token_uk UNIQUE (token_hash)
);
CREATE INDEX sessions_user_idx ON sessions (user_id);

-- Throttling data (kept in the database because the free instance restarts often).
CREATE TABLE login_attempts (
    id         BIGSERIAL PRIMARY KEY,
    email      VARCHAR(254) NOT NULL,
    ip         VARCHAR(64),
    success    BOOLEAN      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX login_attempts_email_idx ON login_attempts (email, created_at DESC);
CREATE INDEX login_attempts_ip_idx ON login_attempts (ip, created_at DESC);

CREATE TABLE mail_log (
    id         BIGSERIAL PRIMARY KEY,
    recipient  VARCHAR(254) NOT NULL,
    kind       VARCHAR(30)  NOT NULL,
    ip         VARCHAR(64),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX mail_log_recipient_idx ON mail_log (recipient, created_at DESC);
CREATE INDEX mail_log_created_idx ON mail_log (created_at DESC);

-- ---------------------------------------------------------------- money
-- Exactly three balances per user.
--   BANK, CASH : money the user has.
--   CREDIT_CARD: money the user OWES on the card (positive = due).
CREATE TABLE accounts (
    id         UUID PRIMARY KEY,
    user_id    UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(12)    NOT NULL CHECK (type IN ('BANK', 'CASH', 'CREDIT_CARD')),
    balance    NUMERIC(12, 2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT accounts_user_type_uk UNIQUE (user_id, type)
);

-- The ledger. Every balance change is one row here.
--   EXPENSE      account = account of the payment mode. BANK/CASH go down, CREDIT_CARD due goes up.
--   INCOME       account = BANK or CASH, goes up.
--   WITHDRAWAL   account = BANK. BANK goes down, CASH goes up (ATM).
--   CARD_PAYMENT account = BANK or CASH (where the money came from). It goes down, CREDIT_CARD due goes down.
--   ADJUSTMENT   account = any. amount is the signed difference applied to that balance.
CREATE TABLE entries (
    id             UUID PRIMARY KEY,
    user_id        UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    client_id      UUID           NOT NULL,
    type           VARCHAR(16)    NOT NULL CHECK (type IN ('EXPENSE', 'INCOME', 'WITHDRAWAL', 'CARD_PAYMENT', 'ADJUSTMENT')),
    amount         NUMERIC(12, 2) NOT NULL,
    account        VARCHAR(12)    NOT NULL CHECK (account IN ('BANK', 'CASH', 'CREDIT_CARD')),
    payment_mode   VARCHAR(12)    CHECK (payment_mode IN ('UPI', 'DEBIT_CARD', 'CREDIT_CARD', 'CASH')),
    description    VARCHAR(120),
    category       VARCHAR(32)    REFERENCES categories (code),
    -- canonical item the AI recognised ("juice" for "juse"); used to group repeated buys
    item           VARCHAR(60),
    ai_category    VARCHAR(32)    REFERENCES categories (code),
    ai_confidence  REAL,
    ai_source      VARCHAR(20),
    needs_review   BOOLEAN        NOT NULL DEFAULT FALSE,
    user_corrected BOOLEAN        NOT NULL DEFAULT FALSE,
    entry_date     DATE           NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT entries_client_uk UNIQUE (user_id, client_id),
    CONSTRAINT entries_amount_ck CHECK ((type = 'ADJUSTMENT' AND amount <> 0) OR (type <> 'ADJUSTMENT' AND amount > 0)),
    CONSTRAINT entries_expense_ck CHECK (
        (type = 'EXPENSE' AND payment_mode IS NOT NULL AND category IS NOT NULL AND description IS NOT NULL)
        OR (type <> 'EXPENSE' AND payment_mode IS NULL AND category IS NULL)
    )
);
CREATE INDEX entries_user_date_idx ON entries (user_id, entry_date DESC, created_at DESC);
CREATE INDEX entries_user_type_date_idx ON entries (user_id, type, entry_date);

-- ---------------------------------------------------------------- AI: what each user taught it
-- Exact memory: "this text is this category" (set when the user corrects or confirms).
CREATE TABLE ai_user_memory (
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    norm_text  VARCHAR(120) NOT NULL,
    category   VARCHAR(32)  NOT NULL REFERENCES categories (code),
    hits       INT          NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, norm_text)
);

-- Per-user word statistics, so a correction also helps similar texts ("juse 2" after "juse").
CREATE TABLE ai_user_token_stats (
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token      VARCHAR(40) NOT NULL,
    category   VARCHAR(32) NOT NULL REFERENCES categories (code),
    weight     REAL        NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, token, category)
);
