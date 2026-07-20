CREATE TABLE "email_outbox_batches" (
  "id" UUID NOT NULL DEFAULT uuidv7(),
  "next_attempt_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "first_attempt_at" TIMESTAMPTZ(6),
  "attempt_count" INTEGER NOT NULL DEFAULT 0,
  "lease_token" UUID,
  "lease_until" TIMESTAMPTZ(6),
  "last_error_code" VARCHAR(64),
  "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updated_at" TIMESTAMPTZ(6) NOT NULL,

  CONSTRAINT "email_outbox_batches_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "email_outbox_batches_attempt_count_check"
    CHECK ("attempt_count" >= 0)
);

CREATE TABLE "email_outbox_entries" (
  "message_id" UUID NOT NULL,
  "batch_id" UUID,
  "batch_position" INTEGER,
  "queued_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT "email_outbox_entries_pkey" PRIMARY KEY ("message_id"),
  CONSTRAINT "email_outbox_entries_batch_assignment_check"
    CHECK (("batch_id" IS NULL) = ("batch_position" IS NULL)),
  CONSTRAINT "email_outbox_entries_batch_position_check"
    CHECK ("batch_position" IS NULL OR "batch_position" BETWEEN 0 AND 99)
);

CREATE UNIQUE INDEX "email_outbox_entries_batch_position_key"
  ON "email_outbox_entries"("batch_id", "batch_position");

CREATE INDEX "idx_email_outbox_batches_ready"
  ON "email_outbox_batches"("next_attempt_at", "lease_until", "id");

CREATE INDEX "idx_email_outbox_entries_unbatched"
  ON "email_outbox_entries"("queued_at", "message_id")
  WHERE "batch_id" IS NULL;

ALTER TABLE "email_outbox_entries"
  ADD CONSTRAINT "email_outbox_entries_message_id_fkey"
  FOREIGN KEY ("message_id") REFERENCES "email_messages"("id")
  ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "email_outbox_entries"
  ADD CONSTRAINT "email_outbox_entries_batch_id_fkey"
  FOREIGN KEY ("batch_id") REFERENCES "email_outbox_batches"("id")
  ON DELETE CASCADE ON UPDATE CASCADE;
