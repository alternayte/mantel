-- Media belongs to the account, not to an album.
--
-- An album becomes an ordered selection from the account's library, so deleting an album keeps the
-- photographs and one photograph sits in two albums with a different caption in each. Position and
-- caption are properties of the membership, not of the photograph.
ALTER TABLE media_item ADD COLUMN account_id uuid REFERENCES account (id) ON DELETE CASCADE;

UPDATE media_item SET account_id = album.account_id
  FROM album WHERE album.id = media_item.album_id;

ALTER TABLE media_item ALTER COLUMN account_id SET NOT NULL;

-- The identity of a file, so the same photograph never uploads twice. Null for media that arrived
-- before the hash existed: the unique index is partial, so those rows do not collide with each
-- other and dedupe simply does not apply to them.
ALTER TABLE media_item ADD COLUMN content_hash text;

-- The library keeps what the camera produced, whatever its type. Renderability is a property of
-- the item rather than a condition of keeping it, and an item nothing can render never queues.
ALTER TABLE media_item ADD COLUMN renderable boolean NOT NULL DEFAULT true;
ALTER TABLE media_item ALTER COLUMN renderable DROP DEFAULT;

CREATE TABLE album_item (
    album_id      uuid NOT NULL REFERENCES album (id) ON DELETE CASCADE,
    media_item_id uuid NOT NULL REFERENCES media_item (id) ON DELETE CASCADE,
    position      integer NOT NULL,
    caption       text,
    created_at    timestamptz NOT NULL,
    PRIMARY KEY (album_id, media_item_id)
);

INSERT INTO album_item (album_id, media_item_id, position, caption, created_at)
SELECT album_id, id, position, caption, created_at FROM media_item;

CREATE INDEX album_item_album_id_position_idx ON album_item (album_id, position);
CREATE INDEX media_item_account_id_created_at_idx ON media_item (account_id, created_at DESC);
CREATE UNIQUE INDEX media_item_account_content_hash_key
    ON media_item (account_id, content_hash) WHERE content_hash IS NOT NULL;

ALTER TABLE media_item DROP COLUMN album_id;
ALTER TABLE media_item DROP COLUMN position;
ALTER TABLE media_item DROP COLUMN caption;

-- `ready` meant every derivative exists. It now has to mean that and nothing else, because an item
-- that is only backed up is also finished, and the album state `ready` is a different thing.
UPDATE media_item SET status = 'shareable' WHERE status = 'ready';
