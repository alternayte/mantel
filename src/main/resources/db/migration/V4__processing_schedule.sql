-- M3: a failed attempt waits before it is claimed again. Without a column the queue would retry a
-- failing item as fast as it can fail.
ALTER TABLE media_item ADD COLUMN next_attempt_at timestamptz;

-- The claim loop reads uploaded items in creation order, oldest first, skipping those still waiting.
CREATE INDEX media_item_claimable_idx ON media_item (status, next_attempt_at, created_at);
