import { getPrismaClient, Prisma, type PrismaClient } from '@/lib/database';
import type {
  ContactWebhookEvent,
  DomainWebhookEvent,
  EmailWebhookEvent,
} from '@/lib/email';
import {
  extractMessageIds,
  getConfiguredResendClient,
  getHeader,
  hydrateReferencedOutboundMessages,
  parseAddress,
  projectInboundEmail,
} from '@/lib/email';
import {
  createWebhookHandler,
  prepareContactEventData,
  prepareDomainEventData,
  prepareEmailEventData,
} from '@/lib/webhook-handler';

async function insertEmailEvent(
  client: PrismaClient,
  event: EmailWebhookEvent,
  svixId: string,
) {
  const existingEvent = await client.emailWebhookEvent.findUnique({
    where: { svixId },
  });
  if (existingEvent && event.type !== 'email.received') {
    return;
  }

  const data = prepareEmailEventData(event);
  await client.emailWebhookEvent.createMany({
    data: [
      {
        svixId,
        eventType: data.event_type,
        eventCreatedAt: data.event_created_at,
        emailId: data.email_id,
        fromAddress: data.from_address,
        toAddresses: data.to_addresses,
        subject: data.subject,
        emailCreatedAt: data.email_created_at,
        broadcastId: data.broadcast_id,
        templateId: data.template_id,
        tags:
          data.tags?.map(({ name, value }) => ({ name, value })) ??
          Prisma.DbNull,
        bounceType: data.bounce_type,
        bounceSubType: data.bounce_sub_type,
        bounceMessage: data.bounce_message,
        bounceDiagnosticCode: data.bounce_diagnostic_code ?? [],
        clickIpAddress: data.click_ip_address,
        clickLink: data.click_link,
        clickTimestamp: data.click_timestamp,
        clickUserAgent: data.click_user_agent,
      },
    ],
    skipDuplicates: true,
  });

  if (event.type === 'email.received') {
    const existingReceivedMessage = await client.emailMessage.findUnique({
      where: { resendEmailId: event.data.email_id },
    });
    const resend = getConfiguredResendClient();
    if (existingReceivedMessage) {
      await hydrateReferencedOutboundMessages(
        client,
        resend,
        existingReceivedMessage.fromAddress,
        [
          ...existingReceivedMessage.referenceInternetMessageIds,
          ...(existingReceivedMessage.inReplyToInternetMessageId
            ? [existingReceivedMessage.inReplyToInternetMessageId]
            : []),
        ],
        existingReceivedMessage.inReplyToInternetMessageId ??
          existingReceivedMessage.referenceInternetMessageIds.at(-1),
      );
      return;
    }

    const receivedEmail = await resend.getReceived(event.data.email_id);
    const inReplyTo = extractMessageIds(
      getHeader(receivedEmail.headers, 'in-reply-to'),
    );
    const references = extractMessageIds(
      getHeader(receivedEmail.headers, 'references'),
    );
    await hydrateReferencedOutboundMessages(
      client,
      resend,
      parseAddress(event.data.from || receivedEmail.from).address,
      [...references, ...inReplyTo],
      inReplyTo.at(-1) ?? references.at(-1),
    );
    await projectInboundEmail(client, event.data, receivedEmail);
  }
}

async function insertContactEvent(
  client: PrismaClient,
  event: ContactWebhookEvent,
  svixId: string,
) {
  const data = prepareContactEventData(event);

  await client.contactWebhookEvent.createMany({
    data: [
      {
        svixId,
        eventType: data.event_type,
        eventCreatedAt: data.event_created_at,
        contactId: data.contact_id,
        audienceId: data.audience_id,
        segmentIds: data.segment_ids,
        email: data.email,
        firstName: data.first_name,
        lastName: data.last_name,
        unsubscribed: data.unsubscribed,
        contactCreatedAt: data.contact_created_at,
        contactUpdatedAt: data.contact_updated_at,
      },
    ],
    skipDuplicates: true,
  });
}

async function insertDomainEvent(
  client: PrismaClient,
  event: DomainWebhookEvent,
  svixId: string,
) {
  const data = prepareDomainEventData(event);

  await client.domainWebhookEvent.createMany({
    data: [
      {
        svixId,
        eventType: data.event_type,
        eventCreatedAt: data.event_created_at,
        domainId: data.domain_id,
        name: data.name,
        status: data.status,
        region: data.region,
        domainCreatedAt: data.domain_created_at,
        records: data.records.map(
          ({ record, name, type, value, ttl, status, priority }) => ({
            record,
            name,
            type,
            value,
            ttl,
            status,
            ...(priority === undefined ? {} : { priority }),
          }),
        ),
      },
    ],
    skipDuplicates: true,
  });
}

export const POST = createWebhookHandler({
  getClient: getPrismaClient,
  insertEmailEvent,
  insertContactEvent,
  insertDomainEvent,
});
