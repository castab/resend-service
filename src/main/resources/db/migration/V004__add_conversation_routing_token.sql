ALTER TABLE "email_conversations"
ADD COLUMN "routing_token" UUID NOT NULL DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX "email_conversations_routing_token_key"
ON "email_conversations"("routing_token");
