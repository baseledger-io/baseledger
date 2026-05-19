-- V0002__replace_projection_tables_for_r2dbc.sql
--
-- pekko-projection-slick was retired due to a Scala 3 incompatibility in
-- Slick's InsertOrUpdate composite-PK detection (SlickOffsetStore fails to
-- commit offsets). Replaced by pekko-projection-r2dbc, which reuses the same
-- r2dbc connection pool as the journal and uses its own offset tables under
-- the `projection_*` prefix (without the `pekko_` prefix).
--
-- See:
--   https://pekko.apache.org/docs/pekko-persistence-r2dbc/current/projection.html

DROP TABLE IF EXISTS pekko_projection_offset_store;
DROP TABLE IF EXISTS pekko_projection_timestamp_offset_store;
DROP TABLE IF EXISTS pekko_projection_management;

-- Primitive offset types (used as a fallback by R2dbcProjection).
CREATE TABLE IF NOT EXISTS projection_offset_store (
  projection_name VARCHAR(255) NOT NULL,
  projection_key  VARCHAR(255) NOT NULL,
  current_offset  VARCHAR(255) NOT NULL,
  manifest        VARCHAR(32)  NOT NULL,
  mergeable       BOOLEAN      NOT NULL,
  last_updated    BIGINT       NOT NULL,
  PRIMARY KEY(projection_name, projection_key)
);

-- TimestampOffset (used by eventsBySlices, which is what WalletProjection uses).
CREATE TABLE IF NOT EXISTS projection_timestamp_offset_store (
  projection_name    VARCHAR(255)             NOT NULL,
  projection_key     VARCHAR(255)             NOT NULL,
  slice              INT                      NOT NULL,
  persistence_id     VARCHAR(255)             NOT NULL,
  seq_nr             BIGINT                   NOT NULL,
  timestamp_offset   timestamp with time zone NOT NULL,
  timestamp_consumed timestamp with time zone NOT NULL,
  PRIMARY KEY(slice, projection_name, timestamp_offset, persistence_id, seq_nr)
);

CREATE TABLE IF NOT EXISTS projection_management (
  projection_name VARCHAR(255) NOT NULL,
  projection_key  VARCHAR(255) NOT NULL,
  paused          BOOLEAN      NOT NULL,
  last_updated    BIGINT       NOT NULL,
  PRIMARY KEY(projection_name, projection_key)
);
