-- CreateEnum
CREATE TYPE "EmailDirection" AS ENUM ('INBOUND', 'OUTBOUND');

-- CreateEnum
CREATE TYPE "EmailMessageState" AS ENUM ('RECEIVED', 'PENDING', 'ACCEPTED', 'FAILED', 'INDETERMINATE');

-- CreateTable
CREATE TABLE "email_conversations" (
    "id" UUID NOT NULL DEFAULT uuidv7(),
    "topic_type" VARCHAR(64),
    "external_topic_id" VARCHAR(255),
    "title" TEXT NOT NULL,
    "subject" TEXT NOT NULL,
    "participant_address" TEXT NOT NULL,
    "participant_name" TEXT,
    "last_message_at" TIMESTAMPTZ(6) NOT NULL,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL,

    CONSTRAINT "email_conversations_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "email_messages" (
    "id" UUID NOT NULL DEFAULT uuidv7(),
    "conversation_id" UUID NOT NULL,
    "parent_message_id" UUID,
    "direction" "EmailDirection" NOT NULL,
    "state" "EmailMessageState" NOT NULL,
    "state_detail" TEXT,
    "resend_email_id" TEXT,
    "internet_message_id" TEXT,
    "in_reply_to_internet_message_id" TEXT,
    "reference_internet_message_ids" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "from_address" TEXT NOT NULL,
    "from_name" TEXT,
    "to_address" TEXT NOT NULL,
    "reply_to_address" TEXT,
    "subject" TEXT NOT NULL,
    "text_body" TEXT,
    "html_body" TEXT,
    "email_created_at" TIMESTAMPTZ(6) NOT NULL,
    "idempotency_key" VARCHAR(256),
    "request_hash" CHAR(64),
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL,

    CONSTRAINT "email_messages_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "email_conversations_topic_key" ON "email_conversations"("topic_type", "external_topic_id");

-- CreateIndex
CREATE INDEX "idx_email_conversations_last_message_at" ON "email_conversations"("last_message_at");

-- CreateIndex
CREATE UNIQUE INDEX "email_messages_resend_email_id_key" ON "email_messages"("resend_email_id");

-- CreateIndex
CREATE UNIQUE INDEX "email_messages_internet_message_id_key" ON "email_messages"("internet_message_id");

-- CreateIndex
CREATE UNIQUE INDEX "email_messages_idempotency_key_key" ON "email_messages"("idempotency_key");

-- CreateIndex
CREATE INDEX "idx_email_messages_conversation_created" ON "email_messages"("conversation_id", "email_created_at", "id");

-- CreateIndex
CREATE INDEX "idx_email_messages_in_reply_to" ON "email_messages"("in_reply_to_internet_message_id");

-- AddForeignKey
ALTER TABLE "email_messages" ADD CONSTRAINT "email_messages_conversation_id_fkey" FOREIGN KEY ("conversation_id") REFERENCES "email_conversations"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "email_messages" ADD CONSTRAINT "email_messages_parent_message_id_fkey" FOREIGN KEY ("parent_message_id") REFERENCES "email_messages"("id") ON DELETE SET NULL ON UPDATE CASCADE;
