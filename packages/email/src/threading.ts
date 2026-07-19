const MESSAGE_ID_PATTERN = /<[^<>\s]+>/g;
const SUBJECT_PREFIX_PATTERN = /^\s*(?:re|fw|fwd)\s*:\s*/i;

export function extractMessageIds(value: string | undefined): string[] {
  return value?.match(MESSAGE_ID_PATTERN) ?? [];
}

export function getHeader(
  headers: Record<string, string> | undefined,
  name: string,
): string | undefined {
  if (!headers) {
    return undefined;
  }

  const target = name.toLowerCase();
  return Object.entries(headers).find(
    ([headerName]) => headerName.toLowerCase() === target,
  )?.[1];
}

export function normalizeSubject(subject: string): string {
  let normalized = subject.trim();
  while (SUBJECT_PREFIX_PATTERN.test(normalized)) {
    normalized = normalized.replace(SUBJECT_PREFIX_PATTERN, '');
  }
  return normalized || subject.trim();
}

export function createReplySubject(subject: string): string {
  return `Re: ${normalizeSubject(subject)}`;
}

export function buildReferences(
  references: string[],
  parentInternetMessageId: string,
): string[] {
  return [...new Set([...references, parentInternetMessageId])];
}

export function parseAddress(value: string): {
  address: string;
  name: string | null;
} {
  const match = value.trim().match(/^(.*?)\s*<([^<>]+)>$/);
  if (!match) {
    return { address: value.trim(), name: null };
  }

  const name = match[1].trim().replace(/^"|"$/g, '');
  return { address: match[2].trim(), name: name || null };
}
