import { getPrismaClient, Prisma } from '@resend-service/database';
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
  isRecord,
  isUuid,
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
      return NextResponse.json(
        { error: 'A conversation already exists for this topic' },
        { status: 409 },
      );
    }
    throw error;
  }

  try {
    const message = await deliverPendingMessage(client, created.messages[0].id);
    return NextResponse.json(
      {
        conversationId: created.id,
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
      where: { id: created.messages[0].id },
    });
    return NextResponse.json(
      {
        error: 'Failed to send email',
        conversationId: created.id,
        message: serializeMessage(message),
      },
      { status: 502 },
    );
  }
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
    typeof topic.title !== 'string' ||
    !topic.title.trim()
  ) {
    return { error: 'topic type, externalId, and title are invalid' };
  }
  if (!isEmailAddress(participant.email)) {
    return { error: 'participant.email must be a valid email address' };
  }
  const text = typeof message.text === 'string' ? message.text : undefined;
  const html = typeof message.html === 'string' ? message.html : undefined;
  if (!text && !html) {
    return { error: 'message.text or message.html is required' };
  }
  if (value.subject !== undefined && typeof value.subject !== 'string') {
    return { error: 'subject must be a string' };
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
