CREATE TABLE accounts (
  id CHAR(36) PRIMARY KEY,
  kind VARCHAR(16) NOT NULL,
  plugin VARCHAR(64),
  acct_key VARCHAR(190),
  owner_player CHAR(36),
  name VARCHAR(190) NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'default',
  created_at BIGINT NOT NULL,
  UNIQUE (plugin, acct_key)
);
CREATE TABLE balances (
  account_id CHAR(36) PRIMARY KEY REFERENCES accounts(id),
  amount BIGINT NOT NULL,
  last_tx BIGINT NOT NULL
);
CREATE TABLE journal (
  tx_id BIGINT PRIMARY KEY,
  ts BIGINT NOT NULL,
  type VARCHAR(64) NOT NULL,
  memo VARCHAR(190) NOT NULL,
  source_plugin VARCHAR(64) NOT NULL,
  actor CHAR(36),
  reference VARCHAR(190),
  reverses_tx BIGINT,
  UNIQUE (source_plugin, reference)
);
CREATE INDEX journal_ts ON journal (ts);
CREATE INDEX journal_plugin_ts ON journal (source_plugin, ts);
CREATE INDEX journal_reverses ON journal (reverses_tx);
CREATE TABLE postings (
  tx_id BIGINT NOT NULL REFERENCES journal(tx_id),
  account_id CHAR(36) NOT NULL REFERENCES accounts(id),
  amount BIGINT NOT NULL,
  PRIMARY KEY (tx_id, account_id)
);
CREATE INDEX postings_account ON postings (account_id, tx_id);
CREATE TABLE players (
  uuid CHAR(36) PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  first_seen BIGINT NOT NULL,
  last_seen BIGINT NOT NULL,
  starter_paid BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX players_name ON players (name);
CREATE TABLE activity_daily (
  day_index BIGINT NOT NULL,
  uuid CHAR(36) NOT NULL,
  minutes INT NOT NULL,
  PRIMARY KEY (day_index, uuid)
);
CREATE TABLE policy_daily (
  day_index BIGINT NOT NULL,
  plugin VARCHAR(64) NOT NULL,
  issued BIGINT NOT NULL,
  destroyed BIGINT NOT NULL,
  treasury_in BIGINT NOT NULL,
  treasury_out BIGINT NOT NULL,
  transferred BIGINT NOT NULL,
  PRIMARY KEY (day_index, plugin)
);
CREATE TABLE clock (k VARCHAR(32) PRIMARY KEY, v BIGINT NOT NULL);
CREATE TABLE server_lock (id INT PRIMARY KEY, holder CHAR(36) NOT NULL, heartbeat BIGINT NOT NULL);
INSERT INTO accounts (id, kind, name, created_at) VALUES ('00000000-0000-0000-0000-000000000001', 'MINT', 'Mint', 0);
INSERT INTO accounts (id, kind, name, created_at) VALUES ('00000000-0000-0000-0000-000000000002', 'TREASURY', 'Treasury', 0);
INSERT INTO balances (account_id, amount, last_tx) VALUES ('00000000-0000-0000-0000-000000000001', 0, 0);
INSERT INTO balances (account_id, amount, last_tx) VALUES ('00000000-0000-0000-0000-000000000002', 0, 0);
