import { PostgreSQLTestClient } from '../helpers/db-clients';
import { createWebhookTests } from '../helpers/test-factory';

createWebhookTests(() => new PostgreSQLTestClient());
