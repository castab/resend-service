declare namespace NodeJS {
  interface ProcessEnv {
    RESEND_WEBHOOK_SECRET: string;
    DATABASE_URL: string;
  }
}
