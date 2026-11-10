-- Borrowing from friends.
--   BORROW  account = BANK (received by UPI) or CASH (received in cash). It goes up; the user now owes the friend.
--   REPAY   account = BANK (paid by UPI) or CASH (paid in cash). It goes down; what is owed to the friend goes down.
-- What the user owes a friend = sum of BORROW - sum of REPAY of that person_key. The application
-- never lets it go below zero.

-- V1 wrote the list of types as an unnamed check on the column, so PostgreSQL chose the name
-- (entries_type_check today). Drop whatever check covers the type column alone instead of trusting
-- that name; the multi-column checks (amount, expense) are named and stay.
DO $$
DECLARE
    found_name text;
BEGIN
    FOR found_name IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'entries'::regclass
          AND c.contype = 'c'
          AND c.conkey = ARRAY[(SELECT a.attnum FROM pg_attribute a
                                WHERE a.attrelid = c.conrelid AND a.attname = 'type')]
    LOOP
        EXECUTE format('ALTER TABLE entries DROP CONSTRAINT %I', found_name);
    END LOOP;
END
$$;

ALTER TABLE entries
    ADD CONSTRAINT entries_type_check
    CHECK (type IN ('EXPENSE', 'INCOME', 'WITHDRAWAL', 'CARD_PAYMENT', 'ADJUSTMENT', 'BORROW', 'REPAY'));

-- person     : the friend's name as the user typed it (cleaned), e.g. "Ravi".
-- person_key : the same name in lower case with single spaces; "Ravi", "ravi" and " RAVI " are one friend.
-- Existing rows keep NULL in both, which is exactly what the check below asks of them.
ALTER TABLE entries
    ADD COLUMN person     VARCHAR(40),
    ADD COLUMN person_key VARCHAR(40);

ALTER TABLE entries
    ADD CONSTRAINT entries_person_ck CHECK (
        (type IN ('BORROW', 'REPAY') AND person IS NOT NULL AND person <> ''
            AND person_key IS NOT NULL AND person_key <> '')
        OR (type NOT IN ('BORROW', 'REPAY') AND person IS NULL AND person_key IS NULL)
    );

-- Money from or to a friend moves through the bank or through cash, never through the card due.
ALTER TABLE entries
    ADD CONSTRAINT entries_debt_account_ck CHECK (type NOT IN ('BORROW', 'REPAY') OR account IN ('BANK', 'CASH'));

-- "What does this user owe each friend": only the borrow and pay-back rows, grouped by friend.
-- type and amount are included so the sums are answered from the index alone.
CREATE INDEX entries_user_person_idx ON entries (user_id, person_key) INCLUDE (type, amount)
    WHERE person_key IS NOT NULL;
