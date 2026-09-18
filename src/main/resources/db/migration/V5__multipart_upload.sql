-- Resumable upload for large files. upload_id is the storage provider's multipart id: null for a
-- single PUT, and null again once the upload completes (SDD.md 4.6).
ALTER TABLE media_item ADD COLUMN upload_id text;
