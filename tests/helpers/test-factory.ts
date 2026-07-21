import { FakeResendServer } from '@test-support/fake-resend-server';
import { TEST_CONFIG } from '@test-support/setup';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildConversationReplyTo } from '@/lib/email';
import { fixtures } from './fixtures';
import { generateSvixId, signPayload } from './svix';

type CollectionName =
  | 'resend_wh_emails'
  | 'resend_wh_contacts'
  | 'resend_wh_domains';

export interface TestDbClient {
  connect(): Promise<void>;
  findBySvixId(table: CollectionName, svixId: string): Promise<unknown>;
  countBySvixId(table: CollectionName, svixId: string): Promise<number>;
  getUuidVersionBySvixId(
    table: CollectionName,
    svixId: string,
  ): Promise<number | null>;
  truncate(table: CollectionName): Promise<void>;
  truncateConversations(): Promise<void>;
  findEmailMessageByResendId(resendEmailId: string): Promise<unknown>;
  getThreadState(childResendEmailId: string): Promise<unknown>;
  createRoutedConversation(
    participantAddress: string,
  ): Promise<{ routingToken: string }>;
  createOutboundWithoutInternetMessageId(
    resendEmailId: string,
    participantAddress: string,
  ): Promise<void>;
  createWaitingInboundMessage(
    resendEmailId: string,
    participantAddress: string,
    parentInternetMessageId: string,
  ): Promise<void>;
  close(): Promise<void>;
}

export function createWebhookTests(createClient: () => TestDbClient) {
  const endpoint = `${TEST_CONFIG.appBaseUrl}/api/webhooks/resend/v1`;

  describe('Resend webhook endpoint', () => {
    let dbClient: TestDbClient;
    const resendServer = new FakeResendServer();

    beforeAll(async () => {
      dbClient = createClient();
      await dbClient.connect();
      resendServer.reset();
      await resendServer.start(TEST_CONFIG.resendApiBaseUrl);
    });

    afterAll(async () => {
      await dbClient.close();
      await resendServer.close();
    });

    beforeEach(async () => {
      await dbClient.truncate('resend_wh_emails');
      await dbClient.truncate('resend_wh_contacts');
      await dbClient.truncate('resend_wh_domains');
      await dbClient.truncateConversations();
      resendServer.reset();
    });

    describe('email events', () => {
      it('stores email.sent event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.sent();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_emails', svixId);
        expect(stored).not.toBeNull();
        expect(
          await dbClient.getUuidVersionBySvixId('resend_wh_emails', svixId),
        ).toBe(7);
      });

      it('stores email.delivered event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.delivered();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_emails', svixId);
        expect(stored).not.toBeNull();
      });

      it('stores email.delivery_delayed event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.deliveryDelayed();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_emails', svixId);
        expect(stored).not.toBeNull();
      });

      it('stores email.complained event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.complained();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_emails', svixId);
        expect(stored).not.toBeNull();
      });

      it('stores email.bounced event with bounce data', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.bounced();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_emails', svixId);
        expect(stored).not.toBeNull();
      });

      it('stores email.opened event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.opened();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_emails', svixId);
        expect(stored).not.toBeNull();
      });

      it('stores email.clicked event with click data', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.clicked();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_emails', svixId);
        expect(stored).not.toBeNull();
      });

      it('stores email.failed event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.failed();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_emails', svixId);
        expect(stored).not.toBeNull();
      });

      it('stores email.scheduled event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.scheduled();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_emails', svixId);
        expect(stored).not.toBeNull();
      });

      it('stores email.suppressed event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.suppressed();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_emails', svixId);
        expect(stored).not.toBeNull();
      });

      it('stores email.received event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.received();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_emails', svixId);
        expect(stored).not.toBeNull();
        const message = await dbClient.findEmailMessageByResendId(
          event.data.email_id,
        );
        expect(message).toMatchObject({
          direction: 'INBOUND',
          text_body: 'Inbound test body',
        });

        resendServer.received.delete(event.data.email_id);
        const duplicateSvixId = generateSvixId();
        const duplicate = signPayload(
          TEST_CONFIG.webhookSecret,
          event,
          duplicateSvixId,
        );
        expect(
          (
            await fetch(endpoint, {
              method: 'POST',
              headers: duplicate.headers,
              body: duplicate.body,
            })
          ).status,
        ).toBe(200);
      });

      it('does not attach another participant by forged ancestry', async () => {
        const parentEvent = fixtures.email.received();
        const parentSvixId = generateSvixId();
        const parentSigned = signPayload(
          TEST_CONFIG.webhookSecret,
          parentEvent,
          parentSvixId,
        );
        expect(
          (
            await fetch(endpoint, {
              method: 'POST',
              headers: parentSigned.headers,
              body: parentSigned.body,
            })
          ).status,
        ).toBe(200);

        const intruderEvent = fixtures.email.received({
          data: {
            email_id: 'em_intruder',
            message_id: '<intruder@example.com>',
            from: 'intruder@example.net',
            to: ['inbox@example.com'],
            subject: 'Re: Received Email',
            created_at: '2026-07-19T04:02:00.000Z',
          },
        });
        resendServer.received.set('em_intruder', {
          id: 'em_intruder',
          message_id: '<intruder@example.com>',
          from: 'intruder@example.net',
          to: ['inbox@example.com'],
          subject: 'Re: Received Email',
          created_at: '2026-07-19T04:02:00.000Z',
          text: 'Forged reply',
          html: null,
          headers: {
            'in-reply-to': '<received123@example.com>',
            references: '<received123@example.com>',
          },
          reply_to: [],
        });
        const intruderSvixId = generateSvixId();
        const intruderSigned = signPayload(
          TEST_CONFIG.webhookSecret,
          intruderEvent,
          intruderSvixId,
        );
        expect(
          (
            await fetch(endpoint, {
              method: 'POST',
              headers: intruderSigned.headers,
              body: intruderSigned.body,
            })
          ).status,
        ).toBe(200);

        expect(
          await dbClient.getThreadState(intruderEvent.data.email_id),
        ).toMatchObject({
          conversation_count: 2,
          parent_internet_message_id: null,
        });
      });

      it('routes ancestry-free replies from to and received_for', async () => {
        const participantAddress = 'routed@example.com';
        const { routingToken } =
          await dbClient.createRoutedConversation(participantAddress);
        const replyTo = buildConversationReplyTo(
          TEST_CONFIG.replyToBaseAddress,
          routingToken,
        );
        const validEvent = fixtures.email.received({
          data: {
            email_id: 'em_forwarded_participant',
            message_id: '<forwarded-participant@example.com>',
            from: participantAddress,
            to: ['forwarder@example.net'],
            received_for: [replyTo],
            subject: 'Forwarded response',
            created_at: '2026-07-19T04:03:00.000Z',
          },
        });
        resendServer.received.set(validEvent.data.email_id, {
          id: validEvent.data.email_id,
          message_id: validEvent.data.message_id as string,
          from: validEvent.data.from,
          to: validEvent.data.to,
          received_for: [replyTo],
          subject: validEvent.data.subject,
          created_at: validEvent.data.created_at,
          text: 'Forwarded participant response',
          html: null,
          headers: {},
          reply_to: [],
        });
        const validSigned = signPayload(
          TEST_CONFIG.webhookSecret,
          validEvent,
          generateSvixId(),
        );
        expect(
          (
            await fetch(endpoint, {
              method: 'POST',
              headers: validSigned.headers,
              body: validSigned.body,
            })
          ).status,
        ).toBe(200);
        expect(
          await dbClient.getThreadState(validEvent.data.email_id),
        ).toMatchObject({
          conversation_count: 1,
          parent_internet_message_id: null,
        });

        const event = fixtures.email.received({
          data: {
            email_id: 'em_routed_reply',
            message_id: '<routed-reply@example.com>',
            from: participantAddress,
            to: [replyTo],
            subject: 'Re: Routed message',
            created_at: '2026-07-19T04:03:00.000Z',
          },
        });
        resendServer.received.set(event.data.email_id, {
          id: event.data.email_id,
          message_id: event.data.message_id as string,
          from: participantAddress,
          to: [replyTo],
          subject: event.data.subject,
          created_at: event.data.created_at,
          text: 'Header-free reply',
          html: null,
          headers: {},
          reply_to: [],
        });

        const signed = signPayload(
          TEST_CONFIG.webhookSecret,
          event,
          generateSvixId(),
        );
        expect(
          (
            await fetch(endpoint, {
              method: 'POST',
              headers: signed.headers,
              body: signed.body,
            })
          ).status,
        ).toBe(200);
        expect(
          await dbClient.getThreadState(event.data.email_id),
        ).toMatchObject({
          conversation_count: 1,
          parent_internet_message_id: null,
        });
      });

      it('rejects a routing token from a different participant', async () => {
        const participantAddress = 'forwarded@example.com';
        const { routingToken } =
          await dbClient.createRoutedConversation(participantAddress);
        const replyTo = buildConversationReplyTo(
          TEST_CONFIG.replyToBaseAddress,
          routingToken,
        );
        const event = fixtures.email.received({
          data: {
            email_id: 'em_forwarded_intruder',
            message_id: '<forwarded-intruder@example.com>',
            from: 'intruder@example.com',
            to: ['forwarder@example.net'],
            received_for: [replyTo],
            subject: 'Forwarded response',
            created_at: '2026-07-19T04:04:00.000Z',
          },
        });
        resendServer.received.set(event.data.email_id, {
          id: event.data.email_id,
          message_id: event.data.message_id as string,
          from: event.data.from,
          to: event.data.to,
          received_for: [replyTo],
          subject: event.data.subject,
          created_at: event.data.created_at,
          text: 'Different sender',
          html: null,
          headers: {},
          reply_to: [],
        });

        const signed = signPayload(
          TEST_CONFIG.webhookSecret,
          event,
          generateSvixId(),
        );
        expect(
          (
            await fetch(endpoint, {
              method: 'POST',
              headers: signed.headers,
              body: signed.body,
            })
          ).status,
        ).toBe(200);
        expect(
          await dbClient.getThreadState(event.data.email_id),
        ).toMatchObject({
          conversation_count: 2,
          parent_internet_message_id: null,
        });
      });

      it('prefers eligible RFC ancestry over a different routing token', async () => {
        const participantAddress = 'conflict@example.com';
        const { routingToken } =
          await dbClient.createRoutedConversation(participantAddress);
        await dbClient.createOutboundWithoutInternetMessageId(
          'rfc-conflict-parent',
          participantAddress,
        );
        const knownParent = '<known-rfc-conflict-parent@resend.test>';
        const replyTo = buildConversationReplyTo(
          TEST_CONFIG.replyToBaseAddress,
          routingToken,
        );
        const event = fixtures.email.received({
          data: {
            email_id: 'em_routing_conflict',
            message_id: '<routing-conflict@example.com>',
            from: participantAddress,
            to: [replyTo],
            subject: 'Re: Threaded message',
            created_at: '2026-07-19T04:05:00.000Z',
          },
        });
        resendServer.received.set(event.data.email_id, {
          id: event.data.email_id,
          message_id: event.data.message_id as string,
          from: participantAddress,
          to: [replyTo],
          subject: event.data.subject,
          created_at: event.data.created_at,
          text: 'Conflicting route signals',
          html: null,
          headers: {
            'in-reply-to': knownParent,
            references: knownParent,
          },
          reply_to: [],
        });

        const signed = signPayload(
          TEST_CONFIG.webhookSecret,
          event,
          generateSvixId(),
        );
        expect(
          (
            await fetch(endpoint, {
              method: 'POST',
              headers: signed.headers,
              body: signed.body,
            })
          ).status,
        ).toBe(200);
        expect(
          await dbClient.getThreadState(event.data.email_id),
        ).toMatchObject({
          conversation_count: 2,
          parent_internet_message_id: knownParent,
        });
      });

      it('links a child that arrives before its parent', async () => {
        const parentMessageId = '<late-parent@example.com>';
        const childEvent = fixtures.email.received({
          data: {
            email_id: 'em_child_first',
            message_id: '<child-first@example.com>',
            from: 'external@example.com',
            to: ['inbox@example.com'],
            subject: 'Re: Delayed parent',
            created_at: '2026-07-19T04:01:00.000Z',
          },
        });
        resendServer.received.set('em_child_first', {
          id: 'em_child_first',
          message_id: '<child-first@example.com>',
          from: 'external@example.com',
          to: ['inbox@example.com'],
          subject: 'Re: Delayed parent',
          created_at: '2026-07-19T04:01:00.000Z',
          text: 'Child arrived first',
          html: null,
          headers: {
            'in-reply-to': parentMessageId,
            references: parentMessageId,
          },
          reply_to: [],
        });

        const childSvixId = generateSvixId();
        const childSigned = signPayload(
          TEST_CONFIG.webhookSecret,
          childEvent,
          childSvixId,
        );
        expect(
          (
            await fetch(endpoint, {
              method: 'POST',
              headers: childSigned.headers,
              body: childSigned.body,
            })
          ).status,
        ).toBe(200);

        const parentEvent = fixtures.email.received({
          data: {
            email_id: 'em_late_parent',
            message_id: parentMessageId,
            from: 'external@example.com',
            to: ['inbox@example.com'],
            subject: 'Delayed parent',
            created_at: '2026-07-19T04:00:00.000Z',
          },
        });
        resendServer.received.set('em_late_parent', {
          id: 'em_late_parent',
          message_id: parentMessageId,
          from: 'external@example.com',
          to: ['inbox@example.com'],
          subject: 'Delayed parent',
          created_at: '2026-07-19T04:00:00.000Z',
          text: 'Parent arrived second',
          html: null,
          headers: {},
          reply_to: [],
        });
        const parentSvixId = generateSvixId();
        const parentSigned = signPayload(
          TEST_CONFIG.webhookSecret,
          parentEvent,
          parentSvixId,
        );
        expect(
          (
            await fetch(endpoint, {
              method: 'POST',
              headers: parentSigned.headers,
              body: parentSigned.body,
            })
          ).status,
        ).toBe(200);

        expect(
          await dbClient.getThreadState(childEvent.data.email_id),
        ).toMatchObject({
          conversation_count: 1,
          parent_internet_message_id: parentMessageId,
        });
      });

      it('hydrates an outbound parent before projecting its inbox reply', async () => {
        const participantAddress = 'replying@example.com';
        const outboundResendId = 'sent-thread-parent';
        const parentMessageId = `<${outboundResendId}@resend.test>`;
        const knownAncestorMessageId = `<known-${outboundResendId}@resend.test>`;
        await dbClient.createOutboundWithoutInternetMessageId(
          outboundResendId,
          participantAddress,
        );
        resendServer.sends.push({
          id: outboundResendId,
          idempotencyKey: 'conversation/test',
          input: {
            from: 'Mailbox <mailbox@example.com>',
            to: [participantAddress],
            subject: 'Threaded message',
            text: 'Opening message',
          },
        });
        const event = fixtures.email.received({
          data: {
            email_id: 'em_inbox_reply',
            message_id: '<inbox-reply@example.com>',
            from: participantAddress,
            to: ['mailbox@example.com'],
            subject: 'Re: Threaded message',
            created_at: '2026-07-19T04:05:00.000Z',
          },
        });
        resendServer.received.set(event.data.email_id, {
          id: event.data.email_id,
          message_id: event.data.message_id as string,
          from: participantAddress,
          to: ['mailbox@example.com'],
          subject: event.data.subject,
          created_at: event.data.created_at,
          text: 'Normal inbox reply',
          html: null,
          headers: {
            'in-reply-to': parentMessageId,
            references: `${knownAncestorMessageId} ${parentMessageId}`,
          },
          reply_to: [],
        });

        const signed = signPayload(
          TEST_CONFIG.webhookSecret,
          event,
          generateSvixId(),
        );
        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);
        expect(
          await dbClient.getThreadState(event.data.email_id),
        ).toMatchObject({
          conversation_count: 1,
          parent_internet_message_id: parentMessageId,
        });
      });

      it('repairs an already projected reply when its webhook is replayed', async () => {
        const participantAddress = 'replay@example.com';
        const outboundResendId = 'sent-replay-parent';
        const inboundResendId = 'em_replay_child';
        const parentMessageId = `<${outboundResendId}@resend.test>`;
        await dbClient.createOutboundWithoutInternetMessageId(
          outboundResendId,
          participantAddress,
        );
        await dbClient.createWaitingInboundMessage(
          inboundResendId,
          participantAddress,
          parentMessageId,
        );
        resendServer.sends.push({
          id: outboundResendId,
          idempotencyKey: 'conversation/replay',
          input: {
            from: 'Mailbox <mailbox@example.com>',
            to: [participantAddress],
            subject: 'Replay message',
            text: 'Opening message',
          },
        });
        const event = fixtures.email.received({
          data: {
            email_id: inboundResendId,
            message_id: '<replay-child@example.com>',
            from: participantAddress,
            to: ['mailbox@example.com'],
            subject: 'Re: Replay message',
            created_at: '2026-07-19T04:06:00.000Z',
          },
        });
        const signed = signPayload(
          TEST_CONFIG.webhookSecret,
          event,
          generateSvixId(),
        );

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);
        expect(await dbClient.getThreadState(inboundResendId)).toMatchObject({
          conversation_count: 1,
          parent_internet_message_id: parentMessageId,
        });
      });

      it('records the ledger event even when inbound retrieval fails', async () => {
        const event = fixtures.email.received({
          data: {
            email_id: 'em_unretrievable',
            message_id: '<unretrievable@example.com>',
            from: 'external@example.com',
            to: ['inbox@example.com'],
            subject: 'Unretrievable Email',
            created_at: '2026-07-19T04:03:00.000Z',
          },
        });
        const svixId = generateSvixId();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);
        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(500);
        expect(
          await dbClient.findBySvixId('resend_wh_emails', svixId),
        ).not.toBeNull();
        expect(
          await dbClient.findEmailMessageByResendId(event.data.email_id),
        ).toBeNull();
      });
    });

    describe('contact events', () => {
      it('stores contact.created event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.contact.created();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId(
          'resend_wh_contacts',
          svixId,
        );
        expect(stored).not.toBeNull();
        expect(
          await dbClient.getUuidVersionBySvixId('resend_wh_contacts', svixId),
        ).toBe(7);
      });

      it('stores contact.updated event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.contact.updated();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId(
          'resend_wh_contacts',
          svixId,
        );
        expect(stored).not.toBeNull();
      });

      it('stores contact.deleted event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.contact.deleted();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId(
          'resend_wh_contacts',
          svixId,
        );
        expect(stored).not.toBeNull();
      });
    });

    describe('domain events', () => {
      it('stores domain.created event with records', async () => {
        const svixId = generateSvixId();
        const event = fixtures.domain.created();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_domains', svixId);
        expect(stored).not.toBeNull();
        expect(
          await dbClient.getUuidVersionBySvixId('resend_wh_domains', svixId),
        ).toBe(7);
      });

      it('stores domain.updated event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.domain.updated();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_domains', svixId);
        expect(stored).not.toBeNull();
      });

      it('stores domain.deleted event', async () => {
        const svixId = generateSvixId();
        const event = fixtures.domain.deleted();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });

        expect(response.status).toBe(200);

        const stored = await dbClient.findBySvixId('resend_wh_domains', svixId);
        expect(stored).not.toBeNull();
      });
    });

    describe('idempotency', () => {
      it('stores event only once when sent twice with same svix-id', async () => {
        const svixId = generateSvixId();
        const event = fixtures.email.sent();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event, svixId);

        const response1 = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });
        expect(response1.status).toBe(200);

        const response2 = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: signed.body,
        });
        expect(response2.status).toBe(200);

        const count = await dbClient.countBySvixId('resend_wh_emails', svixId);
        expect(count).toBe(1);
      });
    });

    describe('error handling', () => {
      it('does not expose the former webhook route', async () => {
        const response = await fetch(
          `${TEST_CONFIG.appBaseUrl}/api/webhooks/v1/resend`,
          { method: 'POST' },
        );

        expect(response.status).toBe(404);
      });

      it('returns 401 for invalid signature', async () => {
        const event = fixtures.email.sent();
        const signed = signPayload(TEST_CONFIG.webhookSecret, event);

        // Tamper with the body after signing
        const tamperedBody = JSON.stringify({ ...event, tampered: true });

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: signed.headers,
          body: tamperedBody,
        });

        expect(response.status).toBe(401);
      });

      it('returns 400 for missing svix headers', async () => {
        const event = fixtures.email.sent();

        const response = await fetch(endpoint, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(event),
        });

        expect(response.status).toBe(400);
      });
    });
  });
}
