-- 0003_ai_and_entitlements
-- Additive and idempotent: safe to re-run. Applied by scripts/migrate-ai-entitlements.mjs, which wraps
-- this whole file in ONE transaction under an advisory lock. Do not add BEGIN/COMMIT here.
-- Server-only tables: none of them is a sync table (lib/api/v1/contracts/sync.ts) and no client
-- writes them. No existing table is touched.

-- 1. Server-verified Pro entitlement. Phase 6 fills it from Google Play; source 'test' rows are only
--    created by scripts/seed-test-entitlement.mjs against local databases. Only the SHA-256 of the
--    purchase token is stored, never the token. Pro iff state IN ('ACTIVE','GRACE') AND expires_at > now.
CREATE TABLE IF NOT EXISTS "subscription_entitlements" (
	"user_id" text NOT NULL,
	"product_id" text NOT NULL,
	"purchase_token_hash" text NOT NULL,
	"state" text NOT NULL,
	"expires_at" bigint NOT NULL,
	"auto_renewing" boolean NOT NULL,
	"last_verified_at" bigint NOT NULL,
	"source" text NOT NULL,
	"created_at" bigint NOT NULL,
	"updated_at" bigint NOT NULL,
	CONSTRAINT "subscription_entitlements_user_id_purchase_token_hash_pk" PRIMARY KEY("user_id","purchase_token_hash"),
	CONSTRAINT "subscription_entitlements_state_check" CHECK ("state" IN ('ACTIVE','GRACE','ON_HOLD','PAUSED','CANCELED','EXPIRED','REVOKED')),
	CONSTRAINT "subscription_entitlements_source_check" CHECK ("source" IN ('play','test'))
);

-- 2. Intelligent reports cached per source-data fingerprint.
CREATE TABLE IF NOT EXISTS "ai_reports" (
	"id" text PRIMARY KEY NOT NULL,
	"user_id" text NOT NULL,
	"kind" text NOT NULL,
	"source_fingerprint" text NOT NULL,
	"payload" jsonb NOT NULL,
	"model" text NOT NULL,
	"created_at" bigint NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS "ai_reports_user_kind_fingerprint_idx" ON "ai_reports" USING btree ("user_id","kind","source_fingerprint");

-- 3. AI response metadata (Coach/mesocycle). One response per (user, clientMessageId).
--    content: final text of a complete Coach response (NULL while incomplete); the server replays it on
--    a retry and uses it, never the client-synced copy, as assistant context.
--    attempt_id + lease_expires_at: the live generation attempt. A retry may take over an incomplete
--    row only once the lease is gone (released or expired); only the current attempt can complete it.
CREATE TABLE IF NOT EXISTS "ai_responses" (
	"id" text PRIMARY KEY NOT NULL,
	"user_id" text NOT NULL,
	"client_message_id" text NOT NULL,
	"kind" text DEFAULT 'coach' NOT NULL,
	"status" text NOT NULL,
	"content" text,
	"attempt_id" text,
	"lease_expires_at" bigint,
	"created_at" bigint NOT NULL,
	"updated_at" bigint NOT NULL,
	CONSTRAINT "ai_responses_status_check" CHECK ("status" IN ('complete','incomplete')),
	CONSTRAINT "ai_responses_kind_check" CHECK ("kind" IN ('coach','mesocycle'))
);
CREATE UNIQUE INDEX IF NOT EXISTS "ai_responses_user_client_message_idx" ON "ai_responses" USING btree ("user_id","client_message_id");
-- For databases where an earlier revision of this file already created the table.
ALTER TABLE "ai_responses" ADD COLUMN IF NOT EXISTS "kind" text DEFAULT 'coach' NOT NULL;
ALTER TABLE "ai_responses" ADD COLUMN IF NOT EXISTS "content" text;
ALTER TABLE "ai_responses" ADD COLUMN IF NOT EXISTS "attempt_id" text;
ALTER TABLE "ai_responses" ADD COLUMN IF NOT EXISTS "lease_expires_at" bigint;
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ai_responses_kind_check') THEN
		ALTER TABLE "ai_responses" ADD CONSTRAINT "ai_responses_kind_check" CHECK ("kind" IN ('coach','mesocycle'));
	END IF;
END $$;

-- 4. User reports of problematic AI responses (one per user and response).
CREATE TABLE IF NOT EXISTS "reported_ai_responses" (
	"id" text PRIMARY KEY NOT NULL,
	"user_id" text NOT NULL,
	"response_id" text NOT NULL,
	"category" text NOT NULL,
	"context" text,
	"created_at" bigint NOT NULL,
	CONSTRAINT "reported_ai_responses_category_check" CHECK ("category" IN ('inaccurate','harmful','medical','offensive','other'))
);
CREATE UNIQUE INDEX IF NOT EXISTS "reported_ai_responses_user_response_idx" ON "reported_ai_responses" USING btree ("user_id","response_id");
