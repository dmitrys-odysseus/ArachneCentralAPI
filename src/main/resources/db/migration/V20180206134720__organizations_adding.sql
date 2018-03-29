CREATE TABLE IF NOT EXISTS organizations (
  id   BIGSERIAL PRIMARY KEY,
  name VARCHAR NOT NULL UNIQUE
);

ALTER TABLE datanodes
  ADD COLUMN IF NOT EXISTS organization_id BIGINT REFERENCES organizations (id);
