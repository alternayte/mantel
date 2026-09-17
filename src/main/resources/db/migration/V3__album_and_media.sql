-- M2: albums and the items in them. Derivative columns are written by the worker at M3 and M4;
-- they exist here because the item row is one row for its whole life.
CREATE TABLE album (
    id            uuid PRIMARY KEY,
    account_id    uuid NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    title         text NOT NULL,
    description   text,
    cover_item_id uuid,
    status        text NOT NULL,
    item_count    integer NOT NULL,
    total_bytes   bigint NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    published_at  timestamptz,
    archived_at   timestamptz
);

CREATE INDEX album_account_id_updated_at_idx ON album (account_id, updated_at);

CREATE TABLE media_item (
    id                uuid PRIMARY KEY,
    album_id          uuid NOT NULL REFERENCES album (id) ON DELETE CASCADE,
    position          integer NOT NULL,
    kind              text NOT NULL,
    original_key      text NOT NULL,
    thumb_key         text,
    display_webp_key  text,
    display_avif_key  text,
    poster_key        text,
    mp4_key           text,
    width             integer,
    height            integer,
    duration_ms       integer,
    byte_size         bigint NOT NULL,
    caption           text,
    status            text NOT NULL,
    attempts          integer NOT NULL,
    last_error        text,
    claimed_at        timestamptz,
    created_at        timestamptz NOT NULL,
    ready_at          timestamptz
);

-- The claim loop orders by created_at within a status, and the viewer reads an album in position
-- order. Position is not unique: a reorder rewrites the whole set and would fight a unique index.
CREATE INDEX media_item_status_created_at_idx ON media_item (status, created_at);
CREATE INDEX media_item_album_id_position_idx ON media_item (album_id, position);

-- The cover is one of the album's own items. Added after media_item exists, because the two
-- tables point at each other.
ALTER TABLE album
    ADD CONSTRAINT album_cover_item_id_fkey
    FOREIGN KEY (cover_item_id) REFERENCES media_item (id) ON DELETE SET NULL;
