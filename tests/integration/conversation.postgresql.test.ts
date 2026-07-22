import { FakeResendServer } from '@test-support/fake-resend-server';
import { TEST_CONFIG } from '@test-support/setup';
import { Client } from 'pg';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { fixtures } from '../helpers/fixtures';
import { generateSvixId, signPayload } from '../helpers/svix';

describe('Private conversation API', () => {
  const resendServer = new FakeResendServer();
  const database = new Client({ connectionString: TEST_CONFIG.postgresql.url });
  const baseUrl = `${TEST_CONFIG.appBaseUrl}/api/conversations/v1`;
  const webhookUrl = `${TEST_CONFIG.appBaseUrl}/api/webhooks/resend/v1`;

  beforeAll(async () => {
    await database.connect();
    resendServer.reset();
    await resendServer.start(TEST_CONFIG.resendApiBaseUrl);
  });

  afterAll(async () => {
    await database.end();
    await resendServer.close();
  });

  beforeEach(async () => {
    await database.query('TRUNCATE TABLE resend_wh_emails');
    await database.query('TRUNCATE TABLE email_outbox_batches CASCADE');
    await database.query('TRUNCATE TABLE email_conversations CASCADE');
    resendServer.reset();
  });

  it('requires bearer authentication', async () => {
    const response = await fetch(`${baseUrl}?assignment=unassigned`);
    expect(response.status).toBe(401);
  });

  it('checks authentication and idempotency before malformed JSON', async () => {
    const unauthorized = await fetch(baseUrl, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{',
    });
    expect(unauthorized.status).toBe(401);

    const missingKey = await fetch(baseUrl, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${TEST_CONFIG.conversationApiKey}`,
        'content-type': 'application/json',
      },
      body: '{',
    });
    expect(missingKey.status).toBe(400);
    await expect(missingKey.json()).resolves.toEqual({
      error: 'A valid Idempotency-Key header is required',
    });

    const malformed = await fetch(baseUrl, {
      method: 'POST',
      headers: headers('malformed-json'),
      body: '{',
    });
    expect(malformed.status).toBe(400);
    await expect(malformed.json()).resolves.toEqual({
      error: 'Request body must be valid JSON',
    });
  });

  it('serves local API documentation and transport fallbacks', async () => {
    const docs = await fetch(`${TEST_CONFIG.appBaseUrl}/docs`);
    expect(docs.status).toBe(200);
    expect(await docs.text()).toContain('/docs/assets/swagger-ui-bundle.js');

    const asset = await fetch(
      `${TEST_CONFIG.appBaseUrl}/docs/assets/swagger-ui-bundle.js`,
    );
    expect(asset.status).toBe(200);
    const contract = await fetch(`${TEST_CONFIG.appBaseUrl}/openapi.json`);
    expect(contract.status).toBe(200);
    expect((await contract.json()).openapi).toBe('3.1.1');

    const unsupported = await fetch(`${baseUrl}/outbox`, {
      method: 'GET',
      headers: headers(),
    });
    expect(unsupported.status).toBe(404);
  });

  it('starts, continues, and hydrates a topic conversation', async () => {
    const created = await createConversation('create-booking-4821');
    expect(created.response.status).toBe(201);
    expect(created.body.message.deliveryState).toBe('unknown');
    expect(created.body.message.deliveredAt).toBeNull();
    expect(created.body.message.internetMessageId).toBe('<sent-1@resend.test>');
    expectRoutingAddress(created.body.message.replyTo);
    expect(resendServer.sends[0].input.reply_to).toBe(
      created.body.message.replyTo,
    );
    expect(created.body.message.replyToName).toBeNull();

    const replyResponse = await fetch(
      `${baseUrl}/${created.body.conversationId}/messages`,
      {
        method: 'POST',
        headers: headers('reply-booking-4821'),
        body: JSON.stringify({ text: 'This is the reply.' }),
      },
    );
    const reply = await replyResponse.json();
    expect(replyResponse.status).toBe(201);
    expect(reply.message.parentMessageId).toBe(created.body.message.id);
    expect(reply.message.replyTo).toBe(created.body.message.replyTo);
    expect(reply.message.replyToName).toBeNull();
    expect(resendServer.sends[1].input.reply_to).toBe(
      created.body.message.replyTo,
    );
    expect(resendServer.sends[1].input.headers).toEqual({
      'In-Reply-To': '<sent-1@resend.test>',
      References: '<sent-1@resend.test>',
    });

    const hydratedResponse = await fetch(`${baseUrl}/topics/booking/4821`, {
      headers: headers(),
    });
    const hydrated = await hydratedResponse.json();
    expect(hydratedResponse.status).toBe(200);
    expect(hydrated.topic).toEqual({
      type: 'booking',
      externalId: '4821',
      title: 'Booking 4821',
    });
    expect(hydrated.messages).toHaveLength(2);
    expect(hydrated.replyToAddress).toBe(created.body.message.replyTo);
    expect(hydrated.messages[1].parentMessageId).toBe(hydrated.messages[0].id);
  });

  it('formats per-message Reply-To aliases for Resend', async () => {
    const created = await createConversation(
      'reply-to-alias-opening',
      createBody('Booking 4821', { replyToName: 'Brayan "Bookings"' }),
    );

    expect(created.response.status).toBe(201);
    expect(created.body.message.replyToName).toBe('Brayan "Bookings"');
    expect(created.body.message.replyTo).toMatch(/@replies\.example\.com$/);
    expect(resendServer.sends[0].input.reply_to).toBe(
      `"Brayan \\"Bookings\\"" <${created.body.message.replyTo}>`,
    );

    const replyResponse = await fetch(
      `${baseUrl}/${created.body.conversationId}/messages`,
      {
        method: 'POST',
        headers: headers('reply-to-alias-reply'),
        body: JSON.stringify({
          text: 'This is the reply.',
          replyToName: 'Support Team',
        }),
      },
    );
    const reply = await replyResponse.json();

    expect(replyResponse.status).toBe(201);
    expect(reply.message.replyTo).toBe(created.body.message.replyTo);
    expect(reply.message.replyToName).toBe('Support Team');
    expect(resendServer.sends[1].input.reply_to).toBe(
      `Support Team <${created.body.message.replyTo}>`,
    );
  });

  it('rejects unsafe Reply-To aliases', async () => {
    const response = await fetch(baseUrl, {
      method: 'POST',
      headers: headers('unsafe-reply-to-alias'),
      body: JSON.stringify(
        createBody('Booking 4821', { replyToName: 'Brayan\r\nBcc: x@y.test' }),
      ),
    });
    const body = await response.json();

    expect(response.status).toBe(400);
    expect(body.error).toBe(
      'message.replyToName must be a header-safe string of at most 256 characters',
    );
  });

  it('reconciles delivery webhooks that arrive before send acceptance', async () => {
    const eventCreatedAt = '2026-07-21T11:00:00.000Z';
    const event = fixtures.email.delivered();
    const signed = signPayload(
      TEST_CONFIG.webhookSecret,
      {
        ...event,
        created_at: eventCreatedAt,
        data: { ...event.data, email_id: 'sent-1' },
      },
      generateSvixId(),
    );
    const webhook = await fetch(webhookUrl, {
      method: 'POST',
      headers: signed.headers,
      body: signed.body,
    });

    const created = await createConversation('early-delivered');
    const hydratedResponse = await fetch(`${baseUrl}/topics/booking/4821`, {
      headers: headers(),
    });
    const hydrated = await hydratedResponse.json();

    expect(webhook.status).toBe(200);
    expect(created.response.status).toBe(201);
    expect(created.body.message.state).toBe('accepted');
    expect(created.body.message.deliveryState).toBe('delivered');
    expect(created.body.message.deliveredAt).toBe(eventCreatedAt);
    expect(hydrated.messages[0].deliveryState).toBe('delivered');
  });

  it('retries temporarily unavailable sent threading metadata', async () => {
    resendServer.sentMetadataFailuresRemaining = 2;

    const created = await createConversation('metadata-retry');

    expect(created.response.status).toBe(201);
    expect(created.body.message.internetMessageId).toBe('<sent-1@resend.test>');
    expect(resendServer.sentMetadataRequestCount).toBe(3);
  });

  it('does not send twice when an idempotency key is retried', async () => {
    const first = await createConversation('same-request');
    const second = await createConversation('same-request');

    expect(first.response.status).toBe(201);
    expect(second.response.status).toBe(200);
    expect(resendServer.sends).toHaveLength(1);
    expect(second.body.message.id).toBe(first.body.message.id);
  });

  it('coordinates concurrent requests with the same idempotency key', async () => {
    const [first, second] = await Promise.all([
      createConversation('concurrent-request'),
      createConversation('concurrent-request'),
    ]);

    expect([first.response.status, second.response.status].sort()).toEqual([
      200, 201,
    ]);
    expect(first.body.message.id).toBe(second.body.message.id);
    expect(resendServer.sends).toHaveLength(1);
  });

  it('keeps failed idempotent requests failed on retry', async () => {
    resendServer.failNextSendStatus = 422;
    const first = await createConversation('failed-request');
    const second = await createConversation('failed-request');

    expect(first.response.status).toBe(502);
    expect(second.response.status).toBe(502);
    expect(first.body.message.state).toBe('failed');
    expect(second.body.message.id).toBe(first.body.message.id);
  });

  it('re-opens a topic conversation after its opening send failed', async () => {
    resendServer.failNextSendStatus = 422;
    const failed = await createConversation('reopen-original');
    expect(failed.response.status).toBe(502);
    expect(failed.body.message.state).toBe('failed');

    const reopenedResponse = await fetch(baseUrl, {
      method: 'POST',
      headers: headers('reopen-retry'),
      body: JSON.stringify({
        topic: { type: 'booking', externalId: '4821', title: 'Booking 4821' },
        participant: { email: 'corrected@example.com', name: 'Person' },
        message: { text: 'Opening message' },
      }),
    });
    const reopened = await reopenedResponse.json();
    expect(reopenedResponse.status).toBe(201);
    expect(reopened.conversationId).toBe(failed.body.conversationId);
    expect(reopened.message.state).toBe('accepted');
    expect(reopened.message.replyTo).toBe(failed.body.message.replyTo);
    expect(resendServer.sends).toHaveLength(1);
    expect(resendServer.sends[0].input.to).toEqual(['corrected@example.com']);

    const conflict = await createConversation('reopen-conflict');
    expect(conflict.response.status).toBe(409);
  });

  it('rejects header-unsafe and oversized topic titles', async () => {
    const crlfResponse = await fetch(baseUrl, {
      method: 'POST',
      headers: headers('crlf-title'),
      body: JSON.stringify(
        createBody('Booking 4821\r\nBcc: victim@example.com'),
      ),
    });
    expect(crlfResponse.status).toBe(400);

    const oversizedResponse = await fetch(baseUrl, {
      method: 'POST',
      headers: headers('oversized-title'),
      body: JSON.stringify(createBody('x'.repeat(256))),
    });
    expect(oversizedResponse.status).toBe(400);
    expect(resendServer.sends).toHaveLength(0);
  });

  it('rejects reuse of an idempotency key with another payload', async () => {
    await createConversation('conflicting-request');
    const response = await fetch(baseUrl, {
      method: 'POST',
      headers: headers('conflicting-request'),
      body: JSON.stringify(createBody('Different title')),
    });
    expect(response.status).toBe(409);
  });

  it('queues an opening message without calling Resend', async () => {
    const queued = await queueConversation('queued-opening', 'queued-1');

    expect(queued.response.status).toBe(202);
    expect(queued.body.message).toMatchObject({
      direction: 'outbound',
      state: 'pending',
      resendEmailId: null,
      replyTo: expect.any(String),
      text: 'Opening message',
    });
    expect(resendServer.sends).toHaveLength(0);
    expectRoutingAddress(queued.body.message.replyTo);
    const { rows } = await database.query(
      `SELECT message.state, entry.message_id
       FROM email_outbox_entries AS entry
       INNER JOIN email_messages AS message ON message.id = entry.message_id`,
    );
    expect(rows).toEqual([
      { state: 'PENDING', message_id: queued.body.message.id },
    ]);

    const duplicate = await queueConversation('queued-opening', 'queued-1');
    expect(duplicate.response.status).toBe(202);
    expect(duplicate.body.message.id).toBe(queued.body.message.id);
    expect(resendServer.sends).toHaveLength(0);
  });

  it('keeps synchronous and outbox idempotency operations distinct', async () => {
    await queueConversation('delivery-mode-key', 'mode-1');

    const response = await fetch(baseUrl, {
      method: 'POST',
      headers: headers('delivery-mode-key'),
      body: JSON.stringify(createBodyForTopic('mode-1')),
    });

    expect(response.status).toBe(409);
    expect(resendServer.sends).toHaveLength(0);
  });

  it('requires the dedicated key and validates the drain limit', async () => {
    const unauthorized = await fetch(`${baseUrl}/outbox/drain`, {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({ limit: 100 }),
    });
    expect(unauthorized.status).toBe(401);

    const invalid = await fetch(`${baseUrl}/outbox/drain`, {
      method: 'POST',
      headers: drainHeaders(),
      body: JSON.stringify({ limit: 101 }),
    });
    expect(invalid.status).toBe(400);
  });

  it('drains queued messages through one ordered Resend batch', async () => {
    const first = await queueConversation('batch-first', 'batch-1', {
      replyToName: 'Batch One',
    });
    const second = await queueConversation('batch-second', 'batch-2');

    const drained = await drainOutbox(100);

    expect(drained.response.status).toBe(200);
    expect(drained.body).toMatchObject({
      claimed: 2,
      accepted: 2,
      failed: 0,
      retryScheduled: 0,
    });
    expect(
      drained.body.results.map(
        (result: { messageId: string }) => result.messageId,
      ),
    ).toEqual([first.body.message.id, second.body.message.id]);
    expect(resendServer.batches).toHaveLength(1);
    expect(resendServer.batches[0].inputs.map(({ to }) => to[0])).toEqual([
      'person@example.com',
      'person@example.com',
    ]);
    expect(
      resendServer.batches[0].inputs.map(({ reply_to }) => reply_to),
    ).toEqual([
      `Batch One <${first.body.message.replyTo}>`,
      second.body.message.replyTo,
    ]);
    const { rows } = await database.query(
      `SELECT state, resend_email_id
       FROM email_messages
       ORDER BY email_created_at, id`,
    );
    expect(rows).toEqual([
      { state: 'ACCEPTED', resend_email_id: 'batch-1' },
      { state: 'ACCEPTED', resend_email_id: 'batch-2' },
    ]);
    expect(
      Number(
        (
          await database.query(
            'SELECT COUNT(*) AS count FROM email_outbox_entries',
          )
        ).rows[0].count,
      ),
    ).toBe(0);
  });

  it('queues and batch-sends a reply with frozen threading headers', async () => {
    const created = await createConversation('reply-parent');
    const response = await fetch(
      `${baseUrl}/${created.body.conversationId}/messages/outbox`,
      {
        method: 'POST',
        headers: headers('queued-reply'),
        body: JSON.stringify({ text: 'Queued reply' }),
      },
    );
    const queued = await response.json();

    expect(response.status).toBe(202);
    expect(queued.message.state).toBe('pending');
    expect(resendServer.sends).toHaveLength(1);

    const drained = await drainOutbox(100);
    expect(drained.body.accepted).toBe(1);
    expect(resendServer.batches[0].inputs[0].headers).toEqual({
      'In-Reply-To': '<sent-1@resend.test>',
      References: '<sent-1@resend.test>',
    });
    expect(resendServer.batches[0].inputs[0].reply_to).toBe(
      created.body.message.replyTo,
    );
  });

  it('retries the exact batch after acceptance followed by disconnect', async () => {
    const queued = await queueConversation('ambiguous-batch', 'ambiguous-1');
    resendServer.disconnectAfterNextBatch = true;

    const first = await drainOutbox(100);
    expect(first.body).toMatchObject({ claimed: 1, retryScheduled: 1 });
    expect(resendServer.batches).toHaveLength(1);
    await database.query(
      `UPDATE email_outbox_batches
       SET next_attempt_at = now() - interval '1 second',
           lease_token = uuidv7(),
           lease_until = now() - interval '1 second'`,
    );

    const second = await drainOutbox(100);
    expect(second.body).toMatchObject({ claimed: 1, accepted: 1 });
    expect(second.body.results[0]).toMatchObject({
      messageId: queued.body.message.id,
      resendEmailId: 'batch-1',
    });
    expect(resendServer.batches).toHaveLength(1);
  });

  it('does not resend a retry batch after the idempotency safety window', async () => {
    await queueConversation('expired-batch', 'expired-batch');
    resendServer.disconnectAfterNextBatch = true;
    expect((await drainOutbox(100)).body.retryScheduled).toBe(1);
    await database.query(
      `UPDATE email_outbox_batches
       SET first_attempt_at = now() - interval '24 hours',
           next_attempt_at = now() - interval '1 second'`,
    );

    const drained = await drainOutbox(100);

    expect(drained.body).toMatchObject({ claimed: 1, indeterminate: 1 });
    expect(resendServer.batches).toHaveLength(1);
  });

  it('treats a provider batch payload mismatch as indeterminate', async () => {
    await queueConversation('mismatch-batch', 'mismatch-batch');
    resendServer.failNextBatchStatus = 500;
    expect((await drainOutbox(100)).body.retryScheduled).toBe(1);
    await database.query(
      "UPDATE email_outbox_batches SET next_attempt_at = now() - interval '1 second'",
    );
    resendServer.failNextBatchStatus = 409;
    resendServer.failNextBatchCode = 'invalid_idempotent_request';

    const drained = await drainOutbox(100);

    expect(drained.body).toMatchObject({ claimed: 1, indeterminate: 1 });
  });

  it('rejects malformed successful batch metadata without accepting messages', async () => {
    await queueConversation('malformed-batch', 'malformed-batch');
    resendServer.malformedNextBatchResponse = true;

    const first = await drainOutbox(100);
    expect(first.body.retryScheduled).toBe(1);
    const pending = await database.query(
      'SELECT state, resend_email_id FROM email_messages',
    );
    expect(pending.rows).toEqual([{ state: 'PENDING', resend_email_id: null }]);
    await database.query(
      "UPDATE email_outbox_batches SET next_attempt_at = now() - interval '1 second'",
    );

    expect((await drainOutbox(100)).body.accepted).toBe(1);
    expect(resendServer.batches).toHaveLength(1);
  });

  it('treats quota rejection as terminal rather than retryable', async () => {
    await queueConversation('quota-batch', 'quota-batch');
    resendServer.failNextBatchStatus = 429;
    resendServer.failNextBatchCode = 'monthly_quota_exceeded';

    const drained = await drainOutbox(100);

    expect(drained.body).toMatchObject({ claimed: 1, failed: 1 });
    const { rows } = await database.query(
      'SELECT COUNT(*)::int AS count FROM email_outbox_batches',
    );
    expect(rows[0].count).toBe(0);
  });

  it('marks every message failed after a permanent batch error', async () => {
    await queueConversation('failed-batch-1', 'failed-batch-1');
    await queueConversation('failed-batch-2', 'failed-batch-2');
    resendServer.failNextBatchStatus = 422;
    resendServer.failNextBatchCode = 'validation_error';

    const drained = await drainOutbox(100);

    expect(drained.body).toMatchObject({ claimed: 2, failed: 2 });
    const { rows } = await database.query(
      'SELECT DISTINCT state FROM email_messages',
    );
    expect(rows).toEqual([{ state: 'FAILED' }]);
  });

  it('lets concurrent drains claim disjoint batches', async () => {
    await Promise.all([
      queueConversation('concurrent-batch-1', 'concurrent-batch-1'),
      queueConversation('concurrent-batch-2', 'concurrent-batch-2'),
      queueConversation('concurrent-batch-3', 'concurrent-batch-3'),
      queueConversation('concurrent-batch-4', 'concurrent-batch-4'),
    ]);

    const [first, second] = await Promise.all([drainOutbox(2), drainOutbox(2)]);

    expect(first.body.claimed + second.body.claimed).toBe(4);
    expect(resendServer.batches).toHaveLength(2);
    expect(new Set(resendServer.sends.map(({ id }) => id)).size).toBe(4);
  });

  it('claims and finalizes the maximum batch size', async () => {
    await database.query(
      `WITH conversation AS (
         INSERT INTO email_conversations
           (topic_type, external_topic_id, title, subject,
            participant_address, last_message_at, updated_at)
         VALUES
           ('bulk', 'maximum', 'Maximum batch', 'Maximum batch',
            'bulk@example.com', now(), now())
         RETURNING id
       ), messages AS (
         INSERT INTO email_messages
           (conversation_id, direction, state,
            reference_internet_message_ids, from_address, to_address, subject,
            text_body, email_created_at, idempotency_key, request_hash,
            updated_at)
         SELECT conversation.id, 'OUTBOUND', 'PENDING', '{}',
                'mailbox@example.com', 'bulk@example.com', 'Maximum batch',
                'Bulk message ' || item, now(), 'bulk-message-' || item,
                repeat('a', 64), now()
         FROM conversation CROSS JOIN generate_series(1, 100) AS item
         RETURNING id
       )
       INSERT INTO email_outbox_entries (message_id)
       SELECT id FROM messages`,
    );

    const drained = await drainOutbox(100);

    expect(drained.body).toMatchObject({ claimed: 100, accepted: 100 });
    expect(resendServer.batches).toHaveLength(1);
    expect(resendServer.batches[0].inputs).toHaveLength(100);
  });

  it('assigns an unassigned conversation to a topic', async () => {
    const { rows } = await database.query<{ id: string }>(
      `INSERT INTO email_conversations
        (title, subject, participant_address, last_message_at, updated_at)
       VALUES ('Inbound question', 'Inbound question', 'person@example.com', now(), now())
       RETURNING id`,
    );
    const response = await fetch(`${baseUrl}/${rows[0].id}`, {
      method: 'PATCH',
      headers: headers(),
      body: JSON.stringify({
        topic: { type: 'booking', externalId: '9911', title: 'Booking 9911' },
      }),
    });
    const body = await response.json();
    expect(response.status).toBe(200);
    expect(body.topic.externalId).toBe('9911');
  });

  it('assigns an unassigned conversation only once under concurrency', async () => {
    const { rows } = await database.query<{ id: string }>(
      `INSERT INTO email_conversations
        (title, subject, participant_address, last_message_at, updated_at)
       VALUES ('Concurrent assignment', 'Concurrent assignment', 'person@example.com', now(), now())
       RETURNING id`,
    );
    const assign = (externalId: string) =>
      fetch(`${baseUrl}/${rows[0].id}`, {
        method: 'PATCH',
        headers: headers(),
        body: JSON.stringify({
          topic: {
            type: 'booking',
            externalId,
            title: `Booking ${externalId}`,
          },
        }),
      });
    const [first, second] = await Promise.all([assign('one'), assign('two')]);

    expect([first.status, second.status].sort()).toEqual([200, 409]);
  });

  async function createConversation(
    idempotencyKey: string,
    body = createBody(),
  ) {
    const response = await fetch(baseUrl, {
      method: 'POST',
      headers: headers(idempotencyKey),
      body: JSON.stringify(body),
    });
    return { response, body: await response.json() };
  }

  async function queueConversation(
    idempotencyKey: string,
    externalId: string,
    messageOverrides: Record<string, string> = {},
  ) {
    const response = await fetch(`${baseUrl}/outbox`, {
      method: 'POST',
      headers: headers(idempotencyKey),
      body: JSON.stringify(createBodyForTopic(externalId, messageOverrides)),
    });
    return { response, body: await response.json() };
  }

  async function drainOutbox(limit: number) {
    const response = await fetch(`${baseUrl}/outbox/drain`, {
      method: 'POST',
      headers: drainHeaders(),
      body: JSON.stringify({ limit }),
    });
    return { response, body: await response.json() };
  }
});

function headers(idempotencyKey?: string): Record<string, string> {
  return {
    authorization: `Bearer ${TEST_CONFIG.conversationApiKey}`,
    'content-type': 'application/json',
    ...(idempotencyKey ? { 'idempotency-key': idempotencyKey } : {}),
  };
}

function drainHeaders(): Record<string, string> {
  return {
    authorization: `Bearer ${TEST_CONFIG.outboxDrainApiKey}`,
    'content-type': 'application/json',
  };
}

function expectRoutingAddress(value: string) {
  const [local, domain] = TEST_CONFIG.replyToBaseAddress.split('@');
  const escapeRegex = (part: string) =>
    part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  expect(value).toMatch(
    new RegExp(
      `^${escapeRegex(local)}\\+c_[0-9a-f]{32}@${escapeRegex(domain)}$`,
    ),
  );
}

function createBody(
  title = 'Booking 4821',
  messageOverrides: Record<string, string> = {},
) {
  return {
    topic: { type: 'booking', externalId: '4821', title },
    participant: { email: 'person@example.com', name: 'Person' },
    message: { text: 'Opening message', ...messageOverrides },
  };
}

function createBodyForTopic(
  externalId: string,
  messageOverrides: Record<string, string> = {},
) {
  return {
    topic: {
      type: 'booking',
      externalId,
      title: `Booking ${externalId}`,
    },
    participant: { email: 'person@example.com', name: 'Person' },
    message: { text: 'Opening message', ...messageOverrides },
  };
}
