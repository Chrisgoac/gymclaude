-- 0001_android_sync_foundation
-- Additive and idempotent: safe to re-run. Applied by scripts/migrate-sync-foundation.mjs, which wraps
-- this whole file in ONE transaction under an advisory lock. Do not add BEGIN/COMMIT here.
-- No renames, drops or NOT NULL changes: the legacy PWA keeps reading and writing these tables.

-- 1. Schema parity: fields the client already stores but the cloud schema lacked.
ALTER TABLE "exercises" ADD COLUMN IF NOT EXISTS "descripcion" text;
ALTER TABLE "exercises" ADD COLUMN IF NOT EXISTS "execution" text;
ALTER TABLE "exercises" ADD COLUMN IF NOT EXISTS "incremento_kg" double precision;
ALTER TABLE "routine_exercises" ADD COLUMN IF NOT EXISTS "reps_objetivo_min" integer;

-- 2. Server-only v1 sync metadata on the 14 synchronized tables (nullable for legacy writers).
ALTER TABLE "exercises" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "routines" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "routine_exercises" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "workout_sessions" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "logged_exercises" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "logged_sets" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "gyms" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "exercise_photos" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "user_settings" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "coach_messages" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "body_metrics" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "progress_photos" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "mesocycles" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;
ALTER TABLE "achievements" ADD COLUMN IF NOT EXISTS "server_version" bigint, ADD COLUMN IF NOT EXISTS "last_device_id" text, ADD COLUMN IF NOT EXISTS "last_mutation_id" text;

-- 3. Sync infrastructure tables.
CREATE TABLE IF NOT EXISTS "sync_mutations" (
	"user_id" text NOT NULL,
	"mutation_id" text NOT NULL,
	"response_json" jsonb NOT NULL,
	"processed_at" bigint NOT NULL,
	CONSTRAINT "sync_mutations_user_id_mutation_id_pk" PRIMARY KEY("user_id","mutation_id")
);

-- table_name holds the contract SyncTableName (camelCase), e.g. 'routineExercises'.
CREATE TABLE IF NOT EXISTS "sync_changes" (
	"sequence" bigserial PRIMARY KEY NOT NULL,
	"user_id" text NOT NULL,
	"table_name" text NOT NULL,
	"record_id" text NOT NULL,
	"changed_at" bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS "api_rate_buckets" (
	"scope" text NOT NULL,
	"subject_hash" text NOT NULL,
	"window_start" bigint NOT NULL,
	"count" integer NOT NULL,
	CONSTRAINT "api_rate_buckets_scope_subject_hash_window_start_pk" PRIMARY KEY("scope","subject_hash","window_start")
);

-- 4. Indexes after columns/tables.
CREATE INDEX IF NOT EXISTS "sync_changes_user_sequence_idx" ON "sync_changes" USING btree ("user_id","sequence");

-- 5. Backfill: journal every pre-migration row (live and tombstone) exactly once, in a
--    deterministic order, and stamp it with its sequence plus the legacy writer tuple.
--    `server_version IS NULL` = not yet journaled, so a re-run never journals a row twice.
DROP TABLE IF EXISTS pg_temp."_sync_backfill";
CREATE TEMP TABLE "_sync_backfill" ("sequence" bigint, "table_name" text, "user_id" text, "record_id" text);

WITH journaled AS (
	INSERT INTO "sync_changes" ("user_id", "table_name", "record_id", "changed_at")
	SELECT "user_id", "table_name", "record_id", "changed_at" FROM (
		SELECT "user_id", 'exercises' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "exercises" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'routines' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "routines" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'routineExercises' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "routine_exercises" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'workoutSessions' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "workout_sessions" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'loggedExercises' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "logged_exercises" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'loggedSets' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "logged_sets" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'gyms' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "gyms" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'exercisePhotos' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "exercise_photos" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'userSettings' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "user_settings" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'coachMessages' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "coach_messages" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'bodyMetrics' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "body_metrics" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'progressPhotos' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "progress_photos" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'mesocycles' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "mesocycles" WHERE "server_version" IS NULL
		UNION ALL
		SELECT "user_id", 'achievements' AS "table_name", "id" AS "record_id", "server_updated_at" AS "changed_at" FROM "achievements" WHERE "server_version" IS NULL
	) AS pending
	ORDER BY "changed_at", "table_name", "user_id", "record_id"
	RETURNING "sequence", "table_name", "user_id", "record_id"
)
INSERT INTO "_sync_backfill" SELECT "sequence", "table_name", "user_id", "record_id" FROM journaled;

UPDATE "exercises" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'exercises' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "routines" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'routines' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "routine_exercises" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'routineExercises' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "workout_sessions" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'workoutSessions' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "logged_exercises" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'loggedExercises' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "logged_sets" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'loggedSets' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "gyms" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'gyms' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "exercise_photos" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'exercisePhotos' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "user_settings" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'userSettings' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "coach_messages" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'coachMessages' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "body_metrics" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'bodyMetrics' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "progress_photos" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'progressPhotos' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "mesocycles" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'mesocycles' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;
UPDATE "achievements" AS t SET "server_version" = b."sequence", "last_device_id" = 'legacy-pwa', "last_mutation_id" = 'legacy-backfill:' || b."sequence"
	FROM "_sync_backfill" AS b
	WHERE b."table_name" = 'achievements' AND t."user_id" = b."user_id" AND t."id" = b."record_id" AND t."server_version" IS NULL;

DROP TABLE pg_temp."_sync_backfill";
