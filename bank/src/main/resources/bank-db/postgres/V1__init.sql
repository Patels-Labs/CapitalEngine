CREATE TABLE loans (id BIGSERIAL PRIMARY KEY, uuid CHAR(36) NOT NULL, principal BIGINT NOT NULL, interest BIGINT NOT NULL, instalment BIGINT NOT NULL, term_days INT NOT NULL, start_day BIGINT NOT NULL, paid_principal BIGINT NOT NULL DEFAULT 0, paid_interest BIGINT NOT NULL DEFAULT 0, arrears BIGINT NOT NULL DEFAULT 0, status VARCHAR(12) NOT NULL, overdue_days INT NOT NULL DEFAULT 0, missed INT NOT NULL DEFAULT 0, last_day BIGINT NOT NULL, closed_day BIGINT);
CREATE TABLE loan_payments (id BIGSERIAL PRIMARY KEY, loan_id BIGINT NOT NULL, day_index BIGINT NOT NULL, kind VARCHAR(8) NOT NULL, due BIGINT NOT NULL, principal BIGINT NOT NULL, interest BIGINT NOT NULL, reference VARCHAR(64) NOT NULL UNIQUE, status VARCHAR(12) NOT NULL);
CREATE TABLE credit (uuid CHAR(36) PRIMARY KEY, credit_limit BIGINT NOT NULL, clean_repayments INT NOT NULL DEFAULT 0, paused_until_day BIGINT);
CREATE TABLE upkeep (uuid CHAR(36) PRIMARY KEY, area BIGINT NOT NULL DEFAULT 0, due BIGINT NOT NULL DEFAULT 0, last_charged_day BIGINT NOT NULL, frozen INT NOT NULL DEFAULT 0, last_warned_day BIGINT NOT NULL DEFAULT 0, seq BIGINT NOT NULL DEFAULT 0, pending BIGINT NOT NULL DEFAULT 0);
CREATE TABLE players (uuid CHAR(36) PRIMARY KEY, last_seen_day BIGINT NOT NULL, saver INT NOT NULL DEFAULT 0);
CREATE TABLE day_runs (day_index BIGINT PRIMARY KEY, catch_up INT NOT NULL, done INT NOT NULL DEFAULT 0, interest_owed BIGINT NOT NULL DEFAULT 0, interest_paid BIGINT NOT NULL DEFAULT 0, scale DOUBLE PRECISION NOT NULL DEFAULT 1, upkeep_collected BIGINT NOT NULL DEFAULT 0);
-- loans are scanned by status and looked up by player; payments by status (recovery) and by loan (the check)
CREATE INDEX bank_loans_status ON loans (status);
CREATE INDEX bank_loans_uuid ON loans (uuid, status);
CREATE INDEX bank_payments_loan ON loan_payments (loan_id, status);
CREATE INDEX bank_payments_status ON loan_payments (status);
