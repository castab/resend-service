-- CreateEnum
CREATE TYPE "EmailDeliveryState" AS ENUM (
  'UNKNOWN',
  'DELIVERED',
  'DELIVERY_DELAYED',
  'BOUNCED',
  'COMPLAINED',
  'SUPPRESSED',
  'FAILED'
);

-- AlterTable
ALTER TABLE "email_messages"
ADD COLUMN "delivery_state" "EmailDeliveryState",
ADD COLUMN "delivery_state_detail" TEXT,
ADD COLUMN "delivery_state_updated_at" TIMESTAMPTZ(6),
ADD COLUMN "delivered_at" TIMESTAMPTZ(6);

-- AlterTable
ALTER TABLE "resend_wh_emails"
ADD COLUMN "delivery_detail" TEXT;

UPDATE "resend_wh_emails"
SET "delivery_detail" = "bounce_message"
WHERE "event_type" = 'email.bounced'
  AND "bounce_message" IS NOT NULL;

UPDATE "email_messages"
SET "delivery_state" = 'UNKNOWN'
WHERE "direction" = 'OUTBOUND'
  AND "delivery_state" IS NULL;

WITH delivered_events AS (
  SELECT "email_id", MIN("event_created_at") AS "delivered_at"
  FROM "resend_wh_emails"
  WHERE "event_type" = 'email.delivered'
  GROUP BY "email_id"
), current_events AS (
  SELECT DISTINCT ON (event_row."email_id")
    event_row."email_id",
    event_row."event_type",
    event_row."event_created_at",
    event_row."delivery_detail"
  FROM "resend_wh_emails" AS event_row
  WHERE event_row."event_type" IN (
      'email.delivered',
      'email.delivery_delayed',
      'email.bounced',
      'email.complained',
      'email.suppressed',
      'email.failed'
    )
    AND (
      event_row."event_type" <> 'email.delivery_delayed'
      OR NOT EXISTS (
        SELECT 1
        FROM "resend_wh_emails" AS terminal_event
        WHERE terminal_event."email_id" = event_row."email_id"
          AND terminal_event."event_type" IN (
            'email.delivered',
            'email.bounced',
            'email.complained',
            'email.suppressed',
            'email.failed'
          )
      )
    )
  ORDER BY event_row."email_id", event_row."event_created_at" DESC,
    event_row."webhook_received_at" DESC, event_row."id" DESC
)
UPDATE "email_messages" AS message
SET "delivery_state" = CASE current_events."event_type"
    WHEN 'email.delivered' THEN 'DELIVERED'::"EmailDeliveryState"
    WHEN 'email.delivery_delayed' THEN 'DELIVERY_DELAYED'::"EmailDeliveryState"
    WHEN 'email.bounced' THEN 'BOUNCED'::"EmailDeliveryState"
    WHEN 'email.complained' THEN 'COMPLAINED'::"EmailDeliveryState"
    WHEN 'email.suppressed' THEN 'SUPPRESSED'::"EmailDeliveryState"
    WHEN 'email.failed' THEN 'FAILED'::"EmailDeliveryState"
  END,
  "delivery_state_detail" = current_events."delivery_detail",
  "delivery_state_updated_at" = current_events."event_created_at",
  "delivered_at" = delivered_events."delivered_at"
FROM current_events
LEFT JOIN delivered_events ON delivered_events."email_id" = current_events."email_id"
WHERE message."direction" = 'OUTBOUND'
  AND message."resend_email_id" = current_events."email_id";
