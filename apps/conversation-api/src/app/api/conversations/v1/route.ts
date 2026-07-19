import {
  getPrismaClient,
  Prisma,
  type PrismaClient,
} from '@resend-service/database';
import {
  hashSendRequest,
  normalizeSubject,
  parseAddress,
} from '@resend-service/email';
import { NextResponse } from 'next/server';
import {
  authorize,
  getPageLimit,
  isEmailAddress,
  isHeaderSafeText,
  isRecord,
  isUuid,
  MAX_BODY_LENGTH,
  MAX_NAME_LENGTH,
  MAX_SUBJECT_LENGTH,
  MAX_TITLE_LENGTH,
  readJson,
  sendResultResponse,
  serializeMessage,
} from '@/lib/api';
import {
  deliverPendingMessage,
  recoverPendingMessage,
} from '@/lib/conversation-service';

export async function POST(request: Request) {
  const unauthorized = authorize(request);
  if (unauthorized) {
    return unauthorized;
  }

  const idempotencyKey = request.headers.get('idempotency-key');
  if (!idempotencyKey || idempotencyKey.length > 256) {
    return NextResponse.json(
      { error: 'A valid Idempotency-Key header is required' },
      { status: 400 },
    );
  }

  const parsed = await readJson(request);
  if ('response' in parsed) {
    return parsed.response;
  }
  const validation = validateCreateBody(parsed.value);
  if ('error' in validation) {
    return NextResponse.json({ error: validation.error }, { status: 400 });
  }

  const configuredFrom = process.env.RESEND_FROM;
  if (!configuredFrom) {
    return NextResponse.json(
      { error: 'Server misconfiguration' },
      { status: 500 },
    );
  }

  const client = getPrismaClient();
  const requestHash = hashSendRequest(validation.value);
  const existing = await client.emailMessage.findUnique({
    where: { idempotencyKey },
    include: { conversation: true },
  });
  if (existing) {
    if (existing.requestHash !== requestHash) {
      return NextResponse.json(
        { error: 'Idempotency key was already used for a different request' },
        { status: 409 },
      );
    }
    const recovered = await recoverPendingMessage(client, existing.id);
    return sendResultResponse(recovered, existing.conversationId);
  }

  const from = parseAddress(configuredFrom);
  const subject = normalizeSubject(
    validation.value.subject ?? validation.value.topic.title,
  );
  const now = new Date();

  let created;
  try {
    created = await client.emailConversation.create({
      data: {
        topicType: validation.value.topic.type,
        externalTopicId: validation.value.topic.externalId,
        title: validation.value.topic.title,
        subject,
        participantAddress: validation.value.participant.email,
        participantName: validation.value.participant.name,
        lastMessageAt: now,
        messages: {
          create: {
            direction: 'OUTBOUND',
            state: 'PENDING',
            fromAddress: from.address,
            fromName: from.name,
            toAddress: validation.value.participant.email,
            subject,
            textBody: validation.value.message.text,
            htmlBody: validation.value.message.html,
            emailCreatedAt: now,
            idempotencyKey,
            requestHash,
          },
        },
      },
      include: { messages: true },
    });
  } catch (error) {
    if (
      error instanceof Prisma.PrismaClientKnownRequestError &&
      error.code === 'P2002'
    ) {
      const raced = await client.emailMessage.findUnique({
        where: { idempotencyKey },
      });
      if (raced) {
        if (raced.requestHash !== requestHash) {
          return NextResponse.json(
            {
              error: 'Idempotency key was already used for a different request',
            },
            { status: 409 },
          );
        }
        const recovered = await recoverPendingMessage(client, raced.id);
        return sendResultResponse(recovered, raced.conversationId);
      }

      let reopened: { conversationId: string; messageId: string } | null;
      try {
        reopened = await reopenFailedTopicConversation(client, {
          value: validation.value,
          from,
          subject,
          idempotencyKey,
          requestHash,
        });
      } catch (reopenError) {
        if (
          reopenError instanceof Prisma.PrismaClientKnownRequestError &&
          reopenError.code === 'P2002'
        ) {
          const racedReopen = await client.emailMessage.findUnique({
            where: { idempotencyKey },
          });
          if (racedReopen && racedReopen.requestHash === requestHash) {
            const recovered = await recoverPendingMessage(
              client,
              racedReopen.id,
            );
            return sendResultResponse(recovered, racedReopen.conversationId);
          }
          return NextResponse.json(
            { error: 'Idempotency key is already in use' },
            { status: 409 },
          );
        }
        throw reopenError;
      }
      if (reopened) {
        return deliverOpeningMessage(
          client,
          reopened.conversationId,
          reopened.messageId,
        );
      }
      return NextResponse.json(
        { error: 'A conversation already exists for this topic' },
        { status: 409 },
      );
    }
    throw error;
  }

  return deliverOpeningMessage(client, created.id, created.messages[0].id);
}

async function deliverOpeningMessage(
  client: PrismaClient,
  conversationId: string,
  messageId: string,
) {
  try {
    const message = await deliverPendingMessage(client, messageId);
    return NextResponse.json(
      {
        conversationId,
        message: serializeMessage(message),
      },
      { status: 201 },
    );
  } catch (error) {
    console.error(
      'Failed to send opening conversation email:',
      error instanceof Error ? error.message : 'Unknown error',
    );
    const message = await client.emailMessage.findUniqueOrThrow({
      where: { id: messageId },
    });
    return NextResponse.json(
      {
        error: 'Failed to send email',
        conversationId,
        message: serializeMessage(message),
      },
      { status: 502 },
    );
  }
}

async function reopenFailedTopicConversation(
  client: PrismaClient,
  input: {
    value: {
      topic: { type: string; externalId: string; title: string };
      participant: { email: string; name: string | null };
      message: { text?: string; html?: string };
    };
    from: { address: string; name: string | null };
    subject: string;
    idempotencyKey: string;
    requestHash: string;
  },
): Promise<{ conversationId: string; messageId: string } | null> {
  const conversation = await client.emailConversation.findUnique({
    where: {
      topicType_externalTopicId: {
        topicType: input.value.topic.type,
        externalTopicId: input.value.topic.externalId,
      },
    },
  });
  if (!conversation) {
    return null;
  }

  const now = new Date();
  return client.$transaction(async (transaction) => {
    await transaction.$queryRaw`
      SELECT pg_advisory_xact_lock(hashtext(${conversation.id}))::text
    `;
    const liveMessage = await transaction.emailMessage.findFirst({
      where: { conversationId: conversation.id, state: { not: 'FAILED' } },
    });
    if (liveMessage) {
      return null;
    }

    await transaction.emailConversation.update({
      where: { id: conversation.id },
      data: {
        title: input.value.topic.title,
        subject: input.subject,
        participantAddress: input.value.participant.email,
        participantName: input.value.participant.name,
        lastMessageAt: now,
      },
    });
    const message = await transaction.emailMessage.create({
      data: {
        conversationId: conversation.id,
        direction: 'OUTBOUND',
        state: 'PENDING',
        fromAddress: input.from.address,
        fromName: input.from.name,
        toAddress: input.value.participant.email,
        subject: input.subject,
        textBody: input.value.message.text ?? null,
        htmlBody: input.value.message.html ?? null,
        emailCreatedAt: now,
        idempotencyKey: input.idempotencyKey,
        requestHash: input.requestHash,
      },
    });
    return { conversationId: conversation.id, messageId: message.id };
  });
}

export async function GET(request: Request) {
  const unauthorized = authorize(request);
  if (unauthorized) {
    return unauthorized;
  }

  const url = new URL(request.url);
  if (url.searchParams.get('assignment') !== 'unassigned') {
    return NextResponse.json(
      { error: 'Only assignment=unassigned is supported' },
      { status: 400 },
    );
  }

  const limit = getPageLimit(request);
  const client = getPrismaClient();
  const beforeValue = url.searchParams.get('before');
  const before = beforeValue ? decodeConversationCursor(beforeValue) : null;
  if (beforeValue && !before) {
    return NextResponse.json(
      { error: 'Invalid conversation cursor' },
      { status: 400 },
    );
  }
  const conversations = await client.emailConversation.findMany({
    where: {
      topicType: null,
      externalTopicId: null,
      ...(before
        ? {
            OR: [
              { lastMessageAt: { lt: before.lastMessageAt } },
              { lastMessageAt: before.lastMessageAt, id: { lt: before.id } },
            ],
          }
        : {}),
    },
    orderBy: [{ lastMessageAt: 'desc' }, { id: 'desc' }],
    take: limit + 1,
  });
  const hasMore = conversations.length > limit;
  const page = conversations.slice(0, limit);
  return NextResponse.json({
    conversations: page.map((conversation) => ({
      id: conversation.id,
      title: conversation.title,
      participant: {
        address: conversation.participantAddress,
        name: conversation.participantName,
      },
      lastMessageAt: conversation.lastMessageAt.toISOString(),
    })),
    page: {
      hasMore,
      before:
        hasMore && page.at(-1) ? encodeConversationCursor(page.at(-1)!) : null,
    },
  });
}

function encodeConversationCursor(conversation: {
  id: string;
  lastMessageAt: Date;
}) {
  return Buffer.from(
    JSON.stringify([conversation.lastMessageAt.toISOString(), conversation.id]),
  ).toString('base64url');
}

function decodeConversationCursor(value: string): {
  id: string;
  lastMessageAt: Date;
} | null {
  try {
    const parsed = JSON.parse(Buffer.from(value, 'base64url').toString('utf8'));
    if (
      !Array.isArray(parsed) ||
      parsed.length !== 2 ||
      typeof parsed[0] !== 'string' ||
      typeof parsed[1] !== 'string' ||
      !isUuid(parsed[1])
    ) {
      return null;
    }
    const lastMessageAt = new Date(parsed[0]);
    return Number.isNaN(lastMessageAt.getTime())
      ? null
      : { lastMessageAt, id: parsed[1] };
  } catch {
    return null;
  }
}

function validateCreateBody(value: unknown):
  | {
      value: {
        topic: { type: string; externalId: string; title: string };
        participant: { email: string; name: string | null };
        subject?: string;
        message: { text?: string; html?: string };
      };
    }
  | { error: string } {
  if (
    !isRecord(value) ||
    !isRecord(value.topic) ||
    !isRecord(value.participant)
  ) {
    return { error: 'topic and participant objects are required' };
  }
  const topic = value.topic;
  const participant = value.participant;
  const message = isRecord(value.message) ? value.message : {};
  if (
    typeof topic.type !== 'string' ||
    !/^[a-z][a-z0-9_-]{0,63}$/.test(topic.type) ||
    typeof topic.externalId !== 'string' ||
    !topic.externalId ||
    topic.externalId.length > 255 ||
    !isHeaderSafeText(topic.title, MAX_TITLE_LENGTH) ||
    !topic.title.trim()
  ) {
    return { error: 'topic type, externalId, and title are invalid' };
  }
  if (!isEmailAddress(participant.email)) {
    return { error: 'participant.email must be a valid email address' };
  }
  if (
    participant.name !== undefined &&
    participant.name !== null &&
    !isHeaderSafeText(participant.name, MAX_NAME_LENGTH)
  ) {
    return { error: 'participant.name is invalid' };
  }
  const text = typeof message.text === 'string' ? message.text : undefined;
  const html = typeof message.html === 'string' ? message.html : undefined;
  if (!text && !html) {
    return { error: 'message.text or message.html is required' };
  }
  if (
    (text?.length ?? 0) > MAX_BODY_LENGTH ||
    (html?.length ?? 0) > MAX_BODY_LENGTH
  ) {
    return { error: 'message.text and message.html are limited to 1 MiB each' };
  }
  if (
    value.subject !== undefined &&
    !isHeaderSafeText(value.subject, MAX_SUBJECT_LENGTH)
  ) {
    return {
      error: 'subject must be a header-safe string of at most 255 characters',
    };
  }
  return {
    value: {
      topic: {
        type: topic.type,
        externalId: topic.externalId,
        title: topic.title.trim(),
      },
      participant: {
        email: participant.email,
        name:
          typeof participant.name === 'string' && participant.name.trim()
            ? participant.name.trim()
            : null,
      },
      ...(typeof value.subject === 'string' && value.subject.trim()
        ? { subject: value.subject.trim() }
        : {}),
      message: { ...(text ? { text } : {}), ...(html ? { html } : {}) },
    },
  };
}
