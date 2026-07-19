import { FakeResendServer } from '@test-support/fake-resend-server';
import { TEST_CONFIG } from '@test-support/setup';
import { Client } from 'pg';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

describe('Private conversation API', () => {
  const resendServer = new FakeResendServer();
  const database = new Client({ connectionString: TEST_CONFIG.postgresql.url });
  const baseUrl = `${TEST_CONFIG.conversationBaseUrl}/api/conversations/v1`;

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
    await database.query('TRUNCATE TABLE email_conversations CASCADE');
    resendServer.reset();
  });

  it('requires bearer authentication', async () => {
    const response = await fetch(`${baseUrl}?assignment=unassigned`);
    expect(response.status).toBe(401);
  });

  it('starts, continues, and hydrates a topic conversation', async () => {
    const created = await createConversation('create-booking-4821');
    expect(created.response.status).toBe(201);
    expect(created.body.message.internetMessageId).toBe('<sent-1@resend.test>');

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
    expect(hydrated.messages[1].parentMessageId).toBe(hydrated.messages[0].id);
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

  it('rejects reuse of an idempotency key with another payload', async () => {
    await createConversation('conflicting-request');
    const response = await fetch(baseUrl, {
      method: 'POST',
      headers: headers('conflicting-request'),
      body: JSON.stringify(createBody('Different title')),
    });
    expect(response.status).toBe(409);
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

  async function createConversation(idempotencyKey: string) {
    const response = await fetch(baseUrl, {
      method: 'POST',
      headers: headers(idempotencyKey),
      body: JSON.stringify(createBody()),
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

function createBody(title = 'Booking 4821') {
  return {
    topic: { type: 'booking', externalId: '4821', title },
    participant: { email: 'person@example.com', name: 'Person' },
    message: { text: 'Opening message' },
  };
}
