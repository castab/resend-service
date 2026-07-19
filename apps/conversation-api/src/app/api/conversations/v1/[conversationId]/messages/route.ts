import { getPrismaClient, Prisma } from '@resend-service/database';
import {
  buildReferences,
  createReplySubject,
  hashSendRequest,
  parseAddress,
} from '@resend-service/email';
import { NextResponse } from 'next/server';
import {
  authorize,
  isRecord,
  isUuid,
  readJson,
  sendResultResponse,
  serializeMessage,
} from '@/lib/api';
import {
  deliverPendingMessage,
  ensureInternetMessageId,
  recoverPendingMessage,
} from '@/lib/conversation-service';

export async function POST(
  request: Request,
  context: { params: Promise<{ conversationId: string }> },
) {
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
  const validation = validateMessageBody(parsed.value);
  if ('error' in validation) {
    return NextResponse.json({ error: validation.error }, { status: 400 });
  }

  const { conversationId } = await context.params;
  if (!isUuid(conversationId)) {
    return NextResponse.json(
      { error: 'Invalid conversation ID' },
      { status: 400 },
    );
  }
  const client = getPrismaClient();
  const requestHash = hashSendRequest({ conversationId, ...validation.value });
  const existing = await client.emailMessage.findUnique({
    where: { idempotencyKey },
  });
  if (existing) {
    if (
      existing.requestHash !== requestHash ||
      existing.conversationId !== conversationId
    ) {
      return NextResponse.json(
        { error: 'Idempotency key was already used for a different request' },
        { status: 409 },
      );
    }
    const recovered = await recoverPendingMessage(client, existing.id);
    return sendResultResponse(recovered, conversationId);
  }

  const conversation = await client.emailConversation.findUnique({
    where: { id: conversationId },
  });
  if (!conversation) {
    return NextResponse.json(
      { error: 'Conversation not found' },
      { status: 404 },
    );
  }

  const parent = validation.value.replyToMessageId
    ? await client.emailMessage.findFirst({
        where: {
          id: validation.value.replyToMessageId,
          conversationId,
        },
      })
    : await client.emailMessage.findFirst({
        where: {
          conversationId,
          state: { in: ['RECEIVED', 'ACCEPTED'] },
        },
        orderBy: [{ emailCreatedAt: 'desc' }, { id: 'desc' }],
      });
  if (!parent) {
    return NextResponse.json(
      { error: 'Reply parent not found' },
      { status: 404 },
    );
  }

  let parentInternetMessageId: string | null;
  try {
    parentInternetMessageId = await ensureInternetMessageId(client, parent.id);
  } catch (error) {
    console.error(
      'Failed to retrieve reply parent metadata:',
      error instanceof Error ? error.message : 'Unknown error',
    );
    return NextResponse.json(
      { error: 'Reply parent threading metadata is unavailable' },
      { status: 503 },
    );
  }
  if (!parentInternetMessageId) {
    return NextResponse.json(
      { error: 'Reply parent threading metadata is unavailable' },
      { status: 409 },
    );
  }

  const configuredFrom = process.env.RESEND_FROM;
  if (!configuredFrom) {
    return NextResponse.json(
      { error: 'Server misconfiguration' },
      { status: 500 },
    );
  }
  const from = parseAddress(configuredFrom);
  const references = buildReferences(
    parent.referenceInternetMessageIds,
    parentInternetMessageId,
  );
  const now = new Date();

  let pending;
  try {
    pending = await client.$transaction(async (transaction) => {
      const message = await transaction.emailMessage.create({
        data: {
          conversationId,
          parentMessageId: parent.id,
          direction: 'OUTBOUND',
          state: 'PENDING',
          inReplyToInternetMessageId: parentInternetMessageId,
          referenceInternetMessageIds: references,
          fromAddress: from.address,
          fromName: from.name,
          toAddress: conversation.participantAddress,
          subject: createReplySubject(conversation.subject),
          textBody: validation.value.text,
          htmlBody: validation.value.html,
          emailCreatedAt: now,
          idempotencyKey,
          requestHash,
        },
      });
      await transaction.$executeRaw`
        UPDATE email_conversations
        SET last_message_at = GREATEST(last_message_at, ${now}),
            updated_at = now()
        WHERE id = ${conversationId}::uuid
      `;
      return message;
    });
  } catch (error) {
    if (
      error instanceof Prisma.PrismaClientKnownRequestError &&
      error.code === 'P2002'
    ) {
      const raced = await client.emailMessage.findUnique({
        where: { idempotencyKey },
      });
      if (
        raced &&
        raced.requestHash === requestHash &&
        raced.conversationId === conversationId
      ) {
        const recovered = await recoverPendingMessage(client, raced.id);
        return sendResultResponse(recovered, conversationId);
      }
      return NextResponse.json(
        { error: 'Idempotency key is already in use' },
        { status: 409 },
      );
    }
    throw error;
  }

  try {
    const message = await deliverPendingMessage(client, pending.id);
    return NextResponse.json(
      { conversationId, message: serializeMessage(message) },
      { status: 201 },
    );
  } catch (error) {
    console.error(
      'Failed to send conversation reply:',
      error instanceof Error ? error.message : 'Unknown error',
    );
    const message = await client.emailMessage.findUniqueOrThrow({
      where: { id: pending.id },
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

function validateMessageBody(
  value: unknown,
):
  | { value: { text?: string; html?: string; replyToMessageId?: string } }
  | { error: string } {
  if (!isRecord(value)) {
    return { error: 'Request body must be an object' };
  }
  const text = typeof value.text === 'string' ? value.text : undefined;
  const html = typeof value.html === 'string' ? value.html : undefined;
  if (!text && !html) {
    return { error: 'text or html is required' };
  }
  if (
    value.replyToMessageId !== undefined &&
    (typeof value.replyToMessageId !== 'string' ||
      !isUuid(value.replyToMessageId))
  ) {
    return { error: 'replyToMessageId must be a UUID' };
  }
  return {
    value: {
      ...(text ? { text } : {}),
      ...(html ? { html } : {}),
      ...(typeof value.replyToMessageId === 'string'
        ? { replyToMessageId: value.replyToMessageId }
        : {}),
    },
  };
}
