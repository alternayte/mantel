-- M1: the creator and the two ways to become one.
DROP TABLE schema_baseline;

CREATE TABLE account (
    id                  uuid PRIMARY KEY,
    email               text NOT NULL,
    github_id           bigint,
    display_name        text,
    storage_quota_bytes bigint NOT NULL,
    storage_used_bytes  bigint NOT NULL,
    created_at          timestamptz NOT NULL,
    deleted_at          timestamptz
);

-- Email identifies an account, case-insensitively, and is reusable once an account is deleted.
CREATE UNIQUE INDEX account_email_key ON account (lower(email)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX account_github_id_key ON account (github_id) WHERE github_id IS NOT NULL AND deleted_at IS NULL;

-- The link in the mail is the secret. Only its hash is stored, so a database leak cannot sign in.
CREATE TABLE magic_link (
    token_hash  text PRIMARY KEY,
    account_id  uuid NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL
);

CREATE INDEX magic_link_account_id_idx ON magic_link (account_id);

-- Same reasoning: the cookie carries a secret, the table carries its hash.
CREATE TABLE session (
    id         text PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX session_account_id_idx ON session (account_id);
