-- 0004_billing_events
-- Additive and idempotent: safe to re-run. Applied by scripts/migrate-billing-events.mjs, which wraps
-- this whole file in ONE transaction under an advisory lock. Do not add BEGIN/COMMIT here.
-- Requires 0003 (subscription_entitlements). Server-only: not a sync table, no client writes it.

-- 1. Google Play Real-Time Developer Notifications (Pub/Sub push). message_id = Pub/Sub messageId:
--    a redelivered message that is already processed is acknowledged without reprocessing.
--    Only the SHA-256 of the purchase token is stored, never the token.
--    processed_at NULL = received but not processed yet (transient Google error; Pub/Sub retries).
CREATE TABLE IF NOT EXISTS "billing_events" (
	"message_id" text PRIMARY KEY NOT NULL,
	"event_time" bigint NOT NULL,
	"notification_kind" text NOT NULL,
	"notification_type" integer,
	"purchase_token_hash" text,
	"outcome" text,
	"received_at" bigint NOT NULL,
	"processed_at" bigint,
	CONSTRAINT "billing_events_kind_check" CHECK ("notification_kind" IN ('subscription','voided','test','unknown','malformed'))
);

-- 2. RTDN looks up the owner of a purchase by token hash alone (the PK leads with user_id).
CREATE INDEX IF NOT EXISTS "subscription_entitlements_purchase_token_hash_idx"
	ON "subscription_entitlements" USING btree ("purchase_token_hash");
