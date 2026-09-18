-- M8: the offline bundle. Built by the worker into storage, never assembled by the API, so the
-- API stays on the metadata path (SDD.md 3.2).
CREATE TABLE album_bundle (
    id           uuid PRIMARY KEY,
    album_id     uuid NOT NULL REFERENCES album (id) ON DELETE CASCADE,
    variant      text NOT NULL,
    -- What the album looked like when this was built. A bundle whose fingerprint no longer matches
    -- the album is stale, and a stale bundle handed to a recipient is a correctness bug.
    fingerprint  text NOT NULL,
    status       text NOT NULL,
    key          text,
    byte_size    bigint,
    attempts     integer NOT NULL,
    last_error   text,
    claimed_at   timestamptz,
    next_attempt_at timestamptz,
    created_at   timestamptz NOT NULL,
    ready_at     timestamptz
);

-- One bundle per album and variant: a rebuild replaces the row rather than adding to it.
CREATE UNIQUE INDEX album_bundle_album_variant_key ON album_bundle (album_id, variant);
CREATE INDEX album_bundle_claimable_idx ON album_bundle (status, next_attempt_at, created_at);
