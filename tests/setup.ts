import path from 'node:path';
import { config } from 'dotenv';

config({ path: path.resolve(__dirname, '../.env.test') });

export const TEST_CONFIG = {
  appBaseUrl: process.env.APP_BASE_URL || 'http://localhost:3000',
  webhookSecret:
    process.env.RESEND_WEBHOOK_SECRET ||
    'whsec_dGVzdF9zZWNyZXRfa2V5X2Zvcl90ZXN0aW5nXzEyMzQ=',
  postgresql: {
    url:
      process.env.POSTGRESQL_URL ||
      'postgres://postgres:postgres@localhost:5432/resend_test',
  },
};
