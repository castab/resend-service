declare namespace NodeJS {
  interface ProcessEnv {
    RESEND_WEBHOOK_SECRET: string;
    POSTGRESQL_URL: string;
  }
}
