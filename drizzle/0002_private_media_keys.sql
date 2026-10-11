-- 0002_private_media_keys
-- Additive and idempotent: safe to re-run. Applied by scripts/migrate-private-media.mjs, which wraps
-- this whole file in ONE transaction under an advisory lock. Do not add BEGIN/COMMIT here.
-- The existing "key" column is the R2 object key (objectKey). Legacy PWA rows keep their public
-- url/key untouched and NULL metadata; v1 rows store url = '/api/media/read/<id>' plus metadata.

-- 1. Media metadata for rows created through the private v1 media service.
ALTER TABLE "exercise_photos" ADD COLUMN IF NOT EXISTS "mime" text, ADD COLUMN IF NOT EXISTS "bytes" integer, ADD COLUMN IF NOT EXISTS "width" integer, ADD COLUMN IF NOT EXISTS "height" integer;
ALTER TABLE "progress_photos" ADD COLUMN IF NOT EXISTS "mime" text, ADD COLUMN IF NOT EXISTS "bytes" integer, ADD COLUMN IF NOT EXISTS "width" integer, ADD COLUMN IF NOT EXISTS "height" integer;

-- 2. Server-only queue of delayed, idempotent object deletions after a row tombstone.
CREATE TABLE IF NOT EXISTS "media_deletions" (
	"object_key" text PRIMARY KEY NOT NULL,
	"user_id" text NOT NULL,
	"due_at" bigint NOT NULL,
	"attempts" integer DEFAULT 0 NOT NULL,
	"last_error" text,
	"created_at" bigint NOT NULL
);

CREATE INDEX IF NOT EXISTS "media_deletions_due_at_idx" ON "media_deletions" USING btree ("due_at");
