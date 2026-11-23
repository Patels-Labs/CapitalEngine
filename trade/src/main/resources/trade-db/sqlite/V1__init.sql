CREATE TABLE world_price (item TEXT PRIMARY KEY, day INTEGER NOT NULL, price INTEGER NOT NULL, prev INTEGER NOT NULL);
CREATE TABLE shipments (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, item TEXT, direction TEXT, qty INTEGER, total INTEGER, tariff INTEGER DEFAULT 0, day INTEGER, ts INTEGER, status TEXT);
CREATE TABLE booms (week INTEGER PRIMARY KEY, item TEXT, multiplier REAL);
CREATE TABLE ports (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, world TEXT, x INTEGER, y INTEGER, z INTEGER, kind TEXT, npc_id TEXT);
CREATE TABLE cargo (uuid TEXT, item TEXT, qty INTEGER NOT NULL, PRIMARY KEY (uuid, item));
-- quotas and budgets sum today's shipments; recovery scans PENDING ones
CREATE INDEX trade_shipments_day ON shipments (day, status);
CREATE INDEX trade_shipments_status ON shipments (status);
