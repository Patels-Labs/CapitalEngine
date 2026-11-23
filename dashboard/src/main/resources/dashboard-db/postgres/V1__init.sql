CREATE TABLE metrics (metric TEXT PRIMARY KEY, label TEXT, unit TEXT, provider TEXT);
CREATE TABLE snapshots (ts BIGINT NOT NULL, metric TEXT NOT NULL, value DOUBLE PRECISION, res TEXT NOT NULL, PRIMARY KEY (metric, res, ts));
CREATE TABLE daily (day_index BIGINT NOT NULL, metric TEXT NOT NULL, value DOUBLE PRECISION, PRIMARY KEY (day_index, metric));
CREATE TABLE alerts (id BIGSERIAL PRIMARY KEY, first_ts BIGINT, last_ts BIGINT, source TEXT, code TEXT, severity TEXT, message TEXT, hits INT, acked_by TEXT, acked_ts BIGINT);
CREATE TABLE sessions (token_hash TEXT PRIMARY KEY, player_uuid TEXT, created BIGINT, expires BIGINT, last_ip TEXT);
-- downsampling scans by resolution and age; the alert list shows open ones newest first
CREATE INDEX dash_snapshots_age ON snapshots (res, ts);
CREATE INDEX dash_alerts_open ON alerts (acked_ts, last_ts);
