import {
  isEmailAddress,
  isHeaderSafeText,
  isRecord,
  isUuid,
  MAX_BODY_LENGTH,
  MAX_NAME_LENGTH,
  MAX_SUBJECT_LENGTH,
  MAX_TITLE_LENGTH,
} from './api';

export type CreateConversationInput = {
  topic: { type: string; externalId: string; title: string };
  participant: { email: string; name: string | null };
  subject?: string;
  message: { text?: string; html?: string; replyToName?: string };
};

function normalizeReplyToName(value: unknown): string | null | undefined {
  if (value === undefined || value === null) {
    return null;
  }
  if (!isHeaderSafeText(value, MAX_NAME_LENGTH)) {
    return undefined;
  }
  const normalized = value.trim();
  if (!normalized) {
    return null;
  }
  return /[<>]/.test(normalized) ? undefined : normalized;
}

export function validateCreateBody(
  value: unknown,
): { value: CreateConversationInput } | { error: string } {
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
  const replyToName = normalizeReplyToName(message.replyToName);
  if (replyToName === undefined) {
    return {
      error:
        'message.replyToName must be a header-safe string of at most 256 characters',
    };
  }
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
      message: {
        ...(text ? { text } : {}),
        ...(html ? { html } : {}),
        ...(replyToName ? { replyToName } : {}),
      },
    },
  };
}

export function validateMessageBody(value: unknown):
  | {
      value: {
        text?: string;
        html?: string;
        replyToMessageId?: string;
        replyToName?: string;
      };
    }
  | { error: string } {
  if (!isRecord(value)) {
    return { error: 'Request body must be an object' };
  }
  const text = typeof value.text === 'string' ? value.text : undefined;
  const html = typeof value.html === 'string' ? value.html : undefined;
  const replyToName = normalizeReplyToName(value.replyToName);
  if (replyToName === undefined) {
    return {
      error:
        'replyToName must be a header-safe string of at most 256 characters',
    };
  }
  if (!text && !html) {
    return { error: 'text or html is required' };
  }
  if (
    (text?.length ?? 0) > MAX_BODY_LENGTH ||
    (html?.length ?? 0) > MAX_BODY_LENGTH
  ) {
    return { error: 'text and html are limited to 1 MiB each' };
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
      ...(replyToName ? { replyToName } : {}),
    },
  };
}
