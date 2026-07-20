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
  readJson,
  sendResultResponse,
  serializeMessage,
} from '@/lib/api';
import {
  type CreateConversationInput,
  validateCreateBody,
} from '@/lib/send-validation';

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
  const requestHash = hashSendRequest({
    operation: 'outbox-opening-v1',
    request: validation.value,
  });
  const existing = await client.emailMessage.findUnique({
    where: { idempotencyKey },
  });
  if (existing) {
    if (existing.requestHash !== requestHash) {
      return NextResponse.json(
        { error: 'Idempotency key was already used for a different request' },
        { status: 409 },
      );
    }
    return sendResultResponse(existing, existing.conversationId);
  }

  const from = parseAddress(configuredFrom);
  const subject = normalizeSubject(
    validation.value.subject ?? validation.value.topic.title,
  );
  const now = new Date();

  try {
    const created = await client.emailConversation.create({
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
            outboxEntry: { create: {} },
          },
        },
      },
      include: { messages: true },
    });
    return NextResponse.json(
      {
        conversationId: created.id,
        message: serializeMessage(created.messages[0]),
      },
      { status: 202 },
    );
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
        return sendResultResponse(raced, raced.conversationId);
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
            return sendResultResponse(racedReopen, racedReopen.conversationId);
          }
          return NextResponse.json(
            { error: 'Idempotency key is already in use' },
            { status: 409 },
          );
        }
        throw reopenError;
      }
      if (reopened) {
        const message = await client.emailMessage.findUniqueOrThrow({
          where: { id: reopened.messageId },
        });
        return NextResponse.json(
          {
            conversationId: reopened.conversationId,
            message: serializeMessage(message),
          },
          { status: 202 },
        );
      }
      return NextResponse.json(
        { error: 'A conversation already exists for this topic' },
        { status: 409 },
      );
    }
    throw error;
  }
}

async function reopenFailedTopicConversation(
  client: PrismaClient,
  input: {
    value: CreateConversationInput;
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
        outboxEntry: { create: {} },
      },
    });
    return { conversationId: conversation.id, messageId: message.id };
  });
}
