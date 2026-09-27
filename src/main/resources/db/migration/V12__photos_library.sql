-- When the photograph was taken. The library orders by it, because a timeline groups by the day a
-- picture was taken and not the day it arrived. The worker writes it from the file where it can;
-- until then an item is placed by when it was uploaded, which every row already has.
ALTER TABLE media_item ADD COLUMN taken_at timestamptz;
UPDATE media_item SET taken_at = created_at;
ALTER TABLE media_item ALTER COLUMN taken_at SET NOT NULL;

-- A deleted item waits here for 30 days before its bytes go. It keeps its state: the trash is a
-- place, not a step in processing.
ALTER TABLE media_item ADD COLUMN trashed_at timestamptz;

-- The library pages on (taken_at, id), newest first, and never shows the trash.
DROP INDEX media_item_account_id_created_at_idx;
CREATE INDEX media_item_library_idx
    ON media_item (account_id, taken_at DESC, id DESC) WHERE trashed_at IS NULL;

-- The trash lists newest deletion first.
CREATE INDEX media_item_trash_idx
    ON media_item (account_id, trashed_at DESC, id DESC) WHERE trashed_at IS NOT NULL;

-- The backfill asks for backed-up photographs with no display WebP on every idle tick of every
-- worker. Once the backfill is done this index is empty, and the question costs nothing.
CREATE INDEX media_item_display_backfill_idx
    ON media_item (created_at) WHERE status = 'backed_up' AND kind = 'photo' AND display_webp_key IS NULL;
