-- Baseline. Flyway owns the schema; Exposed table objects only read it.
-- The tables of SDD.md section 5 arrive with the milestones that use them:
-- account, magic_link, session at M1; album, media_item at M2; share_link at M5.
CREATE TABLE schema_baseline (
    applied_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE schema_baseline IS 'Proves the migration runner works before any domain table exists. Dropped by the first migration that adds a real table.';
