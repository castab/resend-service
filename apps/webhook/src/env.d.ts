declare namespace NodeJS {
  interface ProcessEnv {
    DATABASE_URL: string;
    RESEND_API_KEY?: string;
    RESEND_WEBHOOK_SECRET: string;
  }
}
