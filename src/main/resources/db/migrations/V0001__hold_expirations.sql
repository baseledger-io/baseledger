-- Tracks holds that are scheduled to be auto-released when their TTL expires.
-- Maintained by HoldExpirationProjection (inserts on TokensReserved, deletes on
-- TokensSpent/TokensReleased) and consumed by HoldExpirationDispatcher (cluster
-- singleton that polls for due rows and dispatches ReleaseTokens commands).
CREATE TABLE IF NOT EXISTS hold_expirations (
  hold_id        VARCHAR(255) PRIMARY KEY,
  wallet_id      VARCHAR(255) NOT NULL,
  expires_at_ms  BIGINT       NOT NULL
);

CREATE INDEX IF NOT EXISTS hold_expirations_due_idx
  ON hold_expirations (expires_at_ms);

