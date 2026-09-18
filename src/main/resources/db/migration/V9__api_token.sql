-- M9: scoped, revocable tokens for agents. A token that can enumerate and re-share every album is
-- a data-exfiltration API with good intentions (SDD.md 9), so scope is part of the row.
CREATE TABLE api_token (
    id           uuid PRIMARY KEY,
    account_id   uuid NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    name         text NOT NULL,
    token_hash   text NOT NULL,
    scopes       text[] NOT NULL,
    created_at   timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at   timestamptz
);

CREATE UNIQUE INDEX api_token_hash_key ON api_token (token_hash);
CREATE INDEX api_token_account_id_idx ON api_token (account_id);
