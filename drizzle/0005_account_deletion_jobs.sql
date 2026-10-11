-- 0005_account_deletion_jobs
-- Additive and idempotent: safe to re-run. Applied by scripts/migrate-account-deletion.mjs, which wraps
-- this whole file in ONE transaction under an advisory lock. Do not add BEGIN/COMMIT here.
-- Server-only: not a sync table, no client writes it.

-- One row per account whose deletion was requested (DELETE /api/v1/account or /delete-account).
-- The row existing is the access lock: every /api/v1 route and the legacy sync/photo routes answer
-- 403 ACCOUNT_DELETING for that user. It holds NO user content: user_id_hash is a salted SHA-256 of
-- the Clerk id, and the raw user_id is kept only while the job is pending (needed to resume the R2,
-- Postgres and Clerk steps) and is set to NULL when the job completes.
-- step = next step to run (storage → database → storage_sweep → identity → done). Each step is
-- idempotent; a failure records last_error_code (a stable code, never a message) and the job is
-- resumed by the next DELETE or by /api/internal/account/process-deletions.
-- lease_owner/lease_expires_at fence concurrent runners (a re-sent DELETE and the cron).
CREATE TABLE IF NOT EXISTS "account_deletion_jobs" (
	"user_id_hash" text PRIMARY KEY NOT NULL,
	"user_id" text,
	"status" text NOT NULL,
	"step" text NOT NULL,
	"attempts" integer DEFAULT 0 NOT NULL,
	"last_error_code" text,
	"lease_owner" text,
	"lease_expires_at" bigint,
	"requested_at" bigint NOT NULL,
	"updated_at" bigint NOT NULL,
	"completed_at" bigint,
	CONSTRAINT "account_deletion_jobs_status_check" CHECK ("status" IN ('pending','done')),
	CONSTRAINT "account_deletion_jobs_step_check" CHECK ("step" IN ('storage','database','storage_sweep','identity','done')),
	CONSTRAINT "account_deletion_jobs_user_id_check" CHECK (("status" = 'done') = ("user_id" IS NULL))
);

-- The cron resumes pending jobs whose lease is free.
CREATE INDEX IF NOT EXISTS "account_deletion_jobs_pending_idx"
	ON "account_deletion_jobs" USING btree ("updated_at") WHERE "status" = 'pending';
