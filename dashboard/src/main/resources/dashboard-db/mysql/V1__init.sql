CREATE TABLE metrics (metric VARCHAR(191) PRIMARY KEY, label VARCHAR(255), unit VARCHAR(32), provider VARCHAR(191));
CREATE TABLE snapshots (ts BIGINT NOT NULL, metric VARCHAR(191) NOT NULL, value DOUBLE, res VARCHAR(4) NOT NULL, PRIMARY KEY (metric, res, ts));
CREATE TABLE daily (day_index BIGINT NOT NULL, metric VARCHAR(191) NOT NULL, value DOUBLE, PRIMARY KEY (day_index, metric));
CREATE TABLE alerts (id BIGINT AUTO_INCREMENT PRIMARY KEY, first_ts BIGINT, last_ts BIGINT, source VARCHAR(191), code VARCHAR(191), severity VARCHAR(16), message TEXT, hits INT, acked_by VARCHAR(64), acked_ts BIGINT);
CREATE TABLE sessions (token_hash VARCHAR(64) PRIMARY KEY, player_uuid VARCHAR(64), created BIGINT, expires BIGINT, last_ip VARCHAR(64));
-- downsampling scans by resolution and age; the alert list shows open ones newest first
CREATE INDEX dash_snapshots_age ON snapshots (res, ts);
CREATE INDEX dash_alerts_open ON alerts (acked_ts, last_ts);
