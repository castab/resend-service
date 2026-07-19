import { createHash } from 'node:crypto';
import type { EmailConversation, EmailMessage } from '@resend-service/database';
import { Prisma, type PrismaClient } from '@resend-service/database';
import type { ResendEmail } from './resend-client';
import {
  extractMessageIds,
  getHeader,
  normalizeSubject,
  parseAddress,
} from './threading';
import type { EmailEventData } from './webhook';

export interface TopicIdentity {
  type: string;
  externalId: string;
  title: string;
}

export interface MessageContent {
  text?: string;
  html?: string;
}

export function hashSendRequest(value: unknown): string {
  return createHash('sha256').update(JSON.stringify(value)).digest('hex');
}

export async function projectInboundEmail(
  client: PrismaClient,
  eventData: EmailEventData,
  email: ResendEmail,
): Promise<EmailMessage> {
  const internetMessageId = eventData.message_id ?? email.message_id;
  const inReplyTo = extractMessageIds(
    getHeader(email.headers, 'in-reply-to'),
  ).at(-1);
  const rawReferences = extractMessageIds(
    getHeader(email.headers, 'references'),
  )
    .filter((messageId) => messageId.length <= 998)
    .slice(-100);
  const effectiveParentInternetMessageId = inReplyTo ?? rawReferences.at(-1);
  const references = inReplyTo
    ? [
        ...rawReferences.filter((messageId) => messageId !== inReplyTo),
        inReplyTo,
      ]
    : rawReferences;
  const displayFrom = parseAddress(
    getHeader(email.headers, 'from') ?? email.from,
  );
  const participant = parseAddress(eventData.from || email.from);
  const emailCreatedAt = new Date(email.created_at || eventData.created_at);
  const participantAddress = participant.address.toLowerCase();

  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      return await client.$transaction(
        async (transaction) => {
          const lockIds = [
            ...new Set([internetMessageId, ...references].filter(Boolean)),
          ].sort();
          for (const lockId of lockIds) {
            await transaction.$queryRaw`
              SELECT pg_advisory_xact_lock(hashtext(${lockId}))::text
            `;
          }

          const existing = await transaction.emailMessage.findFirst({
            where: {
              OR: [
                { resendEmailId: eventData.email_id },
                { internetMessageId },
              ],
            },
          });
          if (existing) {
            return existing;
          }

          const ancestry = [...references].reverse();
          const relatedMessages = ancestry.length
            ? await transaction.emailMessage.findMany({
                where: { internetMessageId: { in: ancestry } },
                include: { conversation: true },
              })
            : [];
          const eligibleMessages = relatedMessages.filter(
            (message) =>
              message.conversation.participantAddress.toLowerCase() ===
              participantAddress,
          );
          const parent = effectiveParentInternetMessageId
            ? eligibleMessages.find(
                (message) =>
                  message.internetMessageId ===
                  effectiveParentInternetMessageId,
              )
            : undefined;
          const nearestAncestor = ancestry
            .map((messageId) =>
              eligibleMessages.find(
                (message) => message.internetMessageId === messageId,
              ),
            )
            .find(Boolean);

          const waitingChildren = (
            await transaction.emailMessage.findMany({
              where: {
                parentMessageId: null,
                inReplyToInternetMessageId: internetMessageId,
              },
              include: { conversation: true },
            })
          ).filter(
            (message) =>
              message.conversation.participantAddress.toLowerCase() ===
              participantAddress,
          );
          const waitingConversation =
            waitingChildren.find(
              (message) => message.conversation.topicType !== null,
            )?.conversation ?? waitingChildren[0]?.conversation;

          const parentConversation = parent?.conversation;
          const ancestorConversation = nearestAncestor?.conversation;
          const assignedWaitingConversation = waitingChildren.find(
            (message) => message.conversation.topicType !== null,
          )?.conversation;
          let conversation =
            (parentConversation?.topicType ? parentConversation : undefined) ??
            (ancestorConversation?.topicType
              ? ancestorConversation
              : undefined) ??
            assignedWaitingConversation ??
            parentConversation ??
            ancestorConversation ??
            waitingConversation;
          conversation ??= await transaction.emailConversation.create({
            data: {
              title: normalizeSubject(email.subject),
              subject: normalizeSubject(email.subject),
              participantAddress: participant.address,
              participantName: displayFrom.name,
              lastMessageAt: emailCreatedAt,
            },
          });

          const foreignUnassignedConversationIds = [
            ...new Set(
              [...eligibleMessages, ...waitingChildren]
                .filter(
                  (related) =>
                    related.conversationId !== conversation.id &&
                    related.conversation.topicType === null,
                )
                .map((related) => related.conversationId),
            ),
          ];
          if (foreignUnassignedConversationIds.length) {
            await transaction.emailMessage.updateMany({
              where: {
                conversationId: { in: foreignUnassignedConversationIds },
              },
              data: { conversationId: conversation.id },
            });
            await transaction.emailConversation.deleteMany({
              where: { id: { in: foreignUnassignedConversationIds } },
            });
          }

          const message = await transaction.emailMessage.create({
            data: {
              conversationId: conversation.id,
              parentMessageId: parent?.id,
              direction: 'INBOUND',
              state: 'RECEIVED',
              resendEmailId: eventData.email_id,
              internetMessageId,
              inReplyToInternetMessageId: effectiveParentInternetMessageId,
              referenceInternetMessageIds: references,
              fromAddress: participant.address,
              fromName: displayFrom.name,
              toAddress: email.to[0] ?? eventData.to[0] ?? '',
              replyToAddress: email.reply_to?.[0] ?? null,
              subject: email.subject,
              textBody: email.text,
              htmlBody: email.html,
              emailCreatedAt,
            },
          });

          await transaction.emailMessage.updateMany({
            where: {
              parentMessageId: null,
              inReplyToInternetMessageId: internetMessageId,
              conversationId: conversation.id,
            },
            data: { parentMessageId: message.id },
          });

          const latest = await transaction.emailMessage.aggregate({
            where: { conversationId: conversation.id },
            _max: { emailCreatedAt: true },
          });
          if (latest._max.emailCreatedAt) {
            await transaction.emailConversation.update({
              where: { id: conversation.id },
              data: { lastMessageAt: latest._max.emailCreatedAt },
            });
          }

          return message;
        },
        { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
      );
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2034' &&
        attempt < 2
      ) {
        continue;
      }
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2002'
      ) {
        const existing = await client.emailMessage.findFirst({
          where: {
            OR: [{ resendEmailId: eventData.email_id }, { internetMessageId }],
          },
        });
        if (existing) {
          return existing;
        }
      }
      throw error;
    }
  }

  throw new Error('Inbound projection retry limit exhausted');
}

export type ConversationWithMessages = EmailConversation & {
  messages: EmailMessage[];
};
