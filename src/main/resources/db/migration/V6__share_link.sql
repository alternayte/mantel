-- M5: a revocable, optionally PIN-protected, optionally expiring URL for one album.
CREATE TABLE share_link (
    id         uuid PRIMARY KEY,
    album_id   uuid NOT NULL REFERENCES album (id) ON DELETE CASCADE,
    token      text NOT NULL,
    pin_hash   text,
    expires_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL
);

-- Every viewer request arrives with a token and nothing else, so this lookup is the hot path.
CREATE UNIQUE INDEX share_link_token_key ON share_link (token);
CREATE INDEX share_link_album_id_idx ON share_link (album_id);
