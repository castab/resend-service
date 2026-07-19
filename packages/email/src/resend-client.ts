export interface SendEmailInput {
  from: string;
  to: string[];
  subject: string;
  text?: string;
  html?: string;
  headers?: Record<string, string>;
}

export interface ResendEmail {
  id: string;
  message_id: string;
  from: string;
  to: string[];
  subject: string;
  created_at: string;
  text: string | null;
  html: string | null;
  headers?: Record<string, string>;
  reply_to?: string[];
}

export interface ResendEmailClient {
  send(input: SendEmailInput, idempotencyKey: string): Promise<{ id: string }>;
  getSent(id: string): Promise<ResendEmail>;
  getReceived(id: string): Promise<ResendEmail>;
}

export class ResendApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly responseBody: string,
  ) {
    super(message);
    this.name = 'ResendApiError';
  }
}

export function createResendEmailClient({
  apiKey,
  baseUrl = 'https://api.resend.com',
}: {
  apiKey: string;
  baseUrl?: string;
}): ResendEmailClient {
  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(`${baseUrl}${path}`, {
      ...init,
      headers: {
        authorization: `Bearer ${apiKey}`,
        'content-type': 'application/json',
        'user-agent': 'resend-service/2.0',
        ...init?.headers,
      },
      signal: AbortSignal.timeout(15_000),
    });

    if (!response.ok) {
      const responseBody = await response.text();
      throw new ResendApiError(
        `Resend API request failed with status ${response.status}`,
        response.status,
        responseBody,
      );
    }

    return (await response.json()) as T;
  }

  return {
    send(input, idempotencyKey) {
      return request<{ id: string }>('/emails', {
        method: 'POST',
        headers: { 'idempotency-key': idempotencyKey },
        body: JSON.stringify(input),
      });
    },
    getSent(id) {
      return request<ResendEmail>(`/emails/${encodeURIComponent(id)}`);
    },
    getReceived(id) {
      return request<ResendEmail>(
        `/emails/receiving/${encodeURIComponent(id)}?html_format=cid`,
      );
    },
  };
}

export function getConfiguredResendClient(): ResendEmailClient {
  const apiKey = process.env.RESEND_API_KEY;
  if (!apiKey) {
    throw new Error('Missing RESEND_API_KEY environment variable');
  }

  return createResendEmailClient({
    apiKey,
    baseUrl: process.env.RESEND_API_BASE_URL,
  });
}
