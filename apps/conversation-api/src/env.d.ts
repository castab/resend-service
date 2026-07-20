declare namespace NodeJS {
  interface ProcessEnv {
    CONVERSATION_API_KEY: string;
    DATABASE_URL: string;
    OUTBOX_DRAIN_API_KEY: string;
    RESEND_API_BASE_URL?: string;
    RESEND_API_KEY: string;
    RESEND_FROM: string;
  }
}
