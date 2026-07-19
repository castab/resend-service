import { FakeResendServer } from '@test-support/fake-resend-server';
import { TEST_CONFIG } from '@test-support/setup';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
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
