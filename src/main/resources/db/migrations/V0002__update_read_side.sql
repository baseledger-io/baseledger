-- V0002__update_read_side.sql

-- Add amount to hold_expirations so the projection can recall it on TokensReleased
ALTER TABLE hold_expirations ADD COLUMN amount BIGINT NOT NULL DEFAULT 0;

-- Drop the placeholder wallets table if it exists (the V0000 template table was named 'templates' or 'wallets' in code)
-- Looking at WalletRepository.scala, it was using "wallets". 
-- V0000 created "templates", but WalletRepository.scala had "wallets" hardcoded.
-- Let's ensure the wallets table is defined correctly.
DROP TABLE IF EXISTS wallets;

CREATE TABLE wallets (
  id                VARCHAR(255) PRIMARY KEY,
  available_balance BIGINT       NOT NULL DEFAULT 0,
  reserved_balance  BIGINT       NOT NULL DEFAULT 0,
  last_event_at     BIGINT       NOT NULL,
  updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE wallet_transactions (
  tx_id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  wallet_id       VARCHAR(255) NOT NULL,
  event_type      VARCHAR(50)  NOT NULL, -- ADDED, RESERVED, SPENT, RELEASED
  amount          BIGINT       NOT NULL,
  hold_id         VARCHAR(255),
  idempotency_key VARCHAR(255) NOT NULL,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX wallet_tx_wallet_id_idx ON wallet_transactions (wallet_id);
