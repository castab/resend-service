import { NextResponse } from 'next/server';
import { getPrismaClient } from '@/lib/database';

export async function GET(request: Request) {
  if (new URL(request.url).search) {
    return NextResponse.json(
      { error: 'Health check does not accept query parameters' },
      { status: 400 },
    );
  }
  if (
    !process.env.DATABASE_URL ||
    !process.env.RESEND_API_KEY ||
    !process.env.RESEND_WEBHOOK_SECRET ||
    !process.env.RESEND_FROM ||
    !process.env.CONVERSATION_API_KEY ||
    !process.env.OUTBOX_DRAIN_API_KEY
  ) {
    return NextResponse.json({ status: 'unhealthy' }, { status: 503 });
  }

  try {
    await getPrismaClient().$queryRaw`SELECT 1`;
    return NextResponse.json({ status: 'ok' });
  } catch {
    return NextResponse.json({ status: 'unhealthy' }, { status: 503 });
  }
}
