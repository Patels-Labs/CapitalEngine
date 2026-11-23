CREATE TABLE loans (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, principal INTEGER NOT NULL, interest INTEGER NOT NULL, instalment INTEGER NOT NULL, term_days INTEGER NOT NULL, start_day INTEGER NOT NULL, paid_principal INTEGER NOT NULL DEFAULT 0, paid_interest INTEGER NOT NULL DEFAULT 0, arrears INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, overdue_days INTEGER NOT NULL DEFAULT 0, missed INTEGER NOT NULL DEFAULT 0, last_day INTEGER NOT NULL, closed_day INTEGER);
CREATE TABLE loan_payments (id INTEGER PRIMARY KEY AUTOINCREMENT, loan_id INTEGER NOT NULL, day_index INTEGER NOT NULL, kind TEXT NOT NULL, due INTEGER NOT NULL, principal INTEGER NOT NULL, interest INTEGER NOT NULL, reference TEXT NOT NULL UNIQUE, status TEXT NOT NULL);
CREATE TABLE credit (uuid TEXT PRIMARY KEY, credit_limit INTEGER NOT NULL, clean_repayments INTEGER NOT NULL DEFAULT 0, paused_until_day INTEGER);
CREATE TABLE upkeep (uuid TEXT PRIMARY KEY, area INTEGER NOT NULL DEFAULT 0, due INTEGER NOT NULL DEFAULT 0, last_charged_day INTEGER NOT NULL, frozen INTEGER NOT NULL DEFAULT 0, last_warned_day INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL DEFAULT 0, pending INTEGER NOT NULL DEFAULT 0);
CREATE TABLE players (uuid TEXT PRIMARY KEY, last_seen_day INTEGER NOT NULL, saver INTEGER NOT NULL DEFAULT 0);
CREATE TABLE day_runs (day_index INTEGER PRIMARY KEY, catch_up INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0, interest_owed INTEGER NOT NULL DEFAULT 0, interest_paid INTEGER NOT NULL DEFAULT 0, scale REAL NOT NULL DEFAULT 1, upkeep_collected INTEGER NOT NULL DEFAULT 0);
-- loans are scanned by status and looked up by player; payments by status (recovery) and by loan (the check)
CREATE INDEX bank_loans_status ON loans (status);
CREATE INDEX bank_loans_uuid ON loans (uuid, status);
CREATE INDEX bank_payments_loan ON loan_payments (loan_id, status);
CREATE INDEX bank_payments_status ON loan_payments (status);
