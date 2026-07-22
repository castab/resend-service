-- CreateTable
CREATE TABLE "resend_wh_emails" (
    "id" UUID NOT NULL DEFAULT uuidv7(),
    "svix_id" TEXT NOT NULL,
    "event_type" TEXT NOT NULL,
    "webhook_received_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "event_created_at" TIMESTAMPTZ(6) NOT NULL,
    "email_id" TEXT NOT NULL,
    "from_address" TEXT NOT NULL,
    "to_addresses" TEXT[] NOT NULL,
    "subject" TEXT NOT NULL,
    "email_created_at" TIMESTAMPTZ(6) NOT NULL,
    "broadcast_id" TEXT,
    "template_id" TEXT,
    "tags" JSONB,
    "bounce_type" TEXT,
    "bounce_sub_type" TEXT,
    "bounce_message" TEXT,
    "bounce_diagnostic_code" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    "click_ip_address" TEXT,
    "click_link" TEXT,
    "click_timestamp" TIMESTAMPTZ(6),
    "click_user_agent" TEXT,

    CONSTRAINT "resend_wh_emails_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "resend_wh_contacts" (
    "id" UUID NOT NULL DEFAULT uuidv7(),
    "svix_id" TEXT NOT NULL,
    "event_type" TEXT NOT NULL,
    "webhook_received_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "event_created_at" TIMESTAMPTZ(6) NOT NULL,
    "contact_id" TEXT NOT NULL,
    "audience_id" TEXT,
    "segment_ids" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    "email" TEXT NOT NULL,
    "first_name" TEXT,
    "last_name" TEXT,
    "unsubscribed" BOOLEAN NOT NULL DEFAULT false,
    "contact_created_at" TIMESTAMPTZ(6) NOT NULL,
    "contact_updated_at" TIMESTAMPTZ(6) NOT NULL,

    CONSTRAINT "resend_wh_contacts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "resend_wh_domains" (
    "id" UUID NOT NULL DEFAULT uuidv7(),
    "svix_id" TEXT NOT NULL,
    "event_type" TEXT NOT NULL,
    "webhook_received_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "event_created_at" TIMESTAMPTZ(6) NOT NULL,
    "domain_id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "region" TEXT NOT NULL,
    "domain_created_at" TIMESTAMPTZ(6) NOT NULL,
    "records" JSONB,

    CONSTRAINT "resend_wh_domains_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "resend_wh_emails_svix_id_key" ON "resend_wh_emails"("svix_id");

-- CreateIndex
CREATE INDEX "idx_resend_wh_emails_email_id" ON "resend_wh_emails"("email_id");

-- CreateIndex
CREATE INDEX "idx_resend_wh_emails_event_type" ON "resend_wh_emails"("event_type");

-- CreateIndex
CREATE INDEX "idx_resend_wh_emails_webhook_received_at" ON "resend_wh_emails"("webhook_received_at");

-- CreateIndex
CREATE INDEX "idx_resend_wh_emails_from_address" ON "resend_wh_emails"("from_address");

-- CreateIndex
CREATE UNIQUE INDEX "resend_wh_contacts_svix_id_key" ON "resend_wh_contacts"("svix_id");

-- CreateIndex
CREATE INDEX "idx_resend_wh_contacts_contact_id" ON "resend_wh_contacts"("contact_id");

-- CreateIndex
CREATE INDEX "idx_resend_wh_contacts_event_type" ON "resend_wh_contacts"("event_type");

-- CreateIndex
CREATE INDEX "idx_resend_wh_contacts_webhook_received_at" ON "resend_wh_contacts"("webhook_received_at");

-- CreateIndex
CREATE INDEX "idx_resend_wh_contacts_audience_id" ON "resend_wh_contacts"("audience_id");

-- CreateIndex
CREATE INDEX "idx_resend_wh_contacts_email" ON "resend_wh_contacts"("email");

-- CreateIndex
CREATE UNIQUE INDEX "resend_wh_domains_svix_id_key" ON "resend_wh_domains"("svix_id");

-- CreateIndex
CREATE INDEX "idx_resend_wh_domains_domain_id" ON "resend_wh_domains"("domain_id");

-- CreateIndex
CREATE INDEX "idx_resend_wh_domains_event_type" ON "resend_wh_domains"("event_type");

-- CreateIndex
CREATE INDEX "idx_resend_wh_domains_webhook_received_at" ON "resend_wh_domains"("webhook_received_at");

-- CreateIndex
CREATE INDEX "idx_resend_wh_domains_name" ON "resend_wh_domains"("name");
