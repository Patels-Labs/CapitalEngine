CREATE TABLE metrics (metric TEXT PRIMARY KEY, label TEXT, unit TEXT, provider TEXT);
CREATE TABLE snapshots (ts INTEGER NOT NULL, metric TEXT NOT NULL, value REAL, res TEXT NOT NULL, PRIMARY KEY (metric, res, ts));
CREATE TABLE daily (day_index INTEGER NOT NULL, metric TEXT NOT NULL, value REAL, PRIMARY KEY (day_index, metric));
CREATE TABLE alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, first_ts INTEGER, last_ts INTEGER, source TEXT, code TEXT, severity TEXT, message TEXT, hits INTEGER, acked_by TEXT, acked_ts INTEGER);
CREATE TABLE sessions (token_hash TEXT PRIMARY KEY, player_uuid TEXT, created INTEGER, expires INTEGER, last_ip TEXT);
-- downsampling scans by resolution and age; the alert list shows open ones newest first
CREATE INDEX dash_snapshots_age ON snapshots (res, ts);
CREATE INDEX dash_alerts_open ON alerts (acked_ts, last_ts);
