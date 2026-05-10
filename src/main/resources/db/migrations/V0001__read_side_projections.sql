-- V0001__read_side_projections.sql

-- Tracks holds that are scheduled to be auto-released when their TTL expires.
-- Maintained by HoldExpirationProjection (inserts on TokensReserved, deletes on
-- TokensSpent/TokensReleased) and consumed by HoldExpirationDispatcher (cluster
-- singleton that polls for due rows and dispatches ReleaseTokens commands).
CREATE TABLE IF NOT EXISTS hold_expirations (
  hold_id        VARCHAR(255) PRIMARY KEY,
  wallet_id      VARCHAR(255) NOT NULL,
  amount         BIGINT       NOT NULL,
  expires_at_ms  BIGINT       NOT NULL
);

CREATE INDEX IF NOT EXISTS hold_expirations_due_idx
  ON hold_expirations (expires_at_ms);

CREATE TABLE IF NOT EXISTS wallets (
  id                VARCHAR(255) PRIMARY KEY,
  available_balance BIGINT       NOT NULL DEFAULT 0,
  reserved_balance  BIGINT       NOT NULL DEFAULT 0,
  last_event_at     BIGINT       NOT NULL,
  updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS wallet_transactions (
  tx_id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  wallet_id       VARCHAR(255) NOT NULL,
  event_type      VARCHAR(50)  NOT NULL, -- ADDED, RESERVED, SPENT, RELEASED
  amount          BIGINT       NOT NULL,
  hold_id         VARCHAR(255),
  idempotency_key VARCHAR(255) NOT NULL,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS wallet_tx_wallet_id_idx ON wallet_transactions (wallet_id);
