DROP TABLE IF EXISTS product_feedback;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS handoffs;
DROP TABLE IF EXISTS refunds;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS customers;

CREATE TABLE customers (
  id    TEXT PRIMARY KEY,
  name  TEXT NOT NULL,
  email TEXT NOT NULL
);

CREATE TABLE products (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  category    TEXT NOT NULL,
  color       TEXT,
  price_cents INTEGER NOT NULL,
  stock       INTEGER NOT NULL
);

CREATE TABLE orders (
  id          TEXT PRIMARY KEY,
  customer_id TEXT NOT NULL REFERENCES customers(id),
  status      TEXT NOT NULL CHECK (status IN ('PLACED', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
  created_at  TEXT NOT NULL,
  total_cents INTEGER NOT NULL
);

CREATE TABLE order_items (
  order_id         TEXT NOT NULL REFERENCES orders(id),
  product_id       TEXT NOT NULL REFERENCES products(id),
  qty              INTEGER NOT NULL,
  unit_price_cents INTEGER NOT NULL
);

-- Not used by any tool yet: reserved for the request_refund exercise.
CREATE TABLE refunds (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id        TEXT NOT NULL REFERENCES orders(id),
  amount_cents    INTEGER NOT NULL,
  reason          TEXT,
  status          TEXT NOT NULL,
  idempotency_key TEXT UNIQUE,
  created_at      TEXT NOT NULL
);

CREATE TABLE handoffs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  customer_id TEXT NOT NULL,
  session_id  TEXT,
  summary     TEXT NOT NULL,
  created_at  TEXT NOT NULL
);

-- Free-text product feedback from customers. The store owner reads it, aggregated, through the
-- my_marketplace agent. sentiment is filled lazily by the LLM classifier (NULL = not classified yet).
CREATE TABLE product_feedback (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       TEXT NOT NULL REFERENCES customers(id),
  item          TEXT NOT NULL REFERENCES products(id),
  feedback      TEXT NOT NULL,
  created_at    TEXT NOT NULL,
  sentiment     TEXT CHECK (sentiment IN ('positive', 'neutral', 'negative')),
  classified_at TEXT
);

CREATE TABLE audit_log (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  ts            TEXT NOT NULL,
  session_id    TEXT,
  invocation_id TEXT,
  agent         TEXT,
  kind          TEXT NOT NULL,
  name          TEXT,
  args_json     TEXT,
  result_json   TEXT,
  latency_ms    INTEGER,
  outcome       TEXT
);
