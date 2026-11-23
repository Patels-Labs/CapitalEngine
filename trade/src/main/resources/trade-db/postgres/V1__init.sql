CREATE TABLE world_price (item VARCHAR(190) PRIMARY KEY, day BIGINT NOT NULL, price BIGINT NOT NULL, prev BIGINT NOT NULL);
CREATE TABLE shipments (id BIGSERIAL PRIMARY KEY, uuid CHAR(36), item VARCHAR(190), direction VARCHAR(8), qty BIGINT, total BIGINT, tariff BIGINT DEFAULT 0, day BIGINT, ts BIGINT, status VARCHAR(12));
CREATE TABLE booms (week BIGINT PRIMARY KEY, item VARCHAR(190), multiplier DOUBLE PRECISION);
CREATE TABLE ports (id BIGSERIAL PRIMARY KEY, name VARCHAR(64) UNIQUE, world VARCHAR(190), x INT, y INT, z INT, kind VARCHAR(8), npc_id CHAR(36));
CREATE TABLE cargo (uuid CHAR(36) NOT NULL, item VARCHAR(190) NOT NULL, qty BIGINT NOT NULL, PRIMARY KEY (uuid, item));
-- quotas and budgets sum today's shipments; recovery scans PENDING ones
CREATE INDEX trade_shipments_day ON shipments (day, status);
CREATE INDEX trade_shipments_status ON shipments (status);
