-- The name the creator's file arrived with. Without it a download is a folder of 001-display.webp,
-- which is nobody's album.
ALTER TABLE media_item ADD COLUMN filename text;
