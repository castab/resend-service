import { TEST_CONFIG } from '@test-support/setup';
import { Client } from 'pg';

type TableName =
  | 'resend_wh_emails'
  | 'resend_wh_contacts'
  | 'resend_wh_domains';

export class PostgreSQLTestClient {
  private client: Client | null = null;

  async connect() {
    this.client = new Client({
      connectionString: TEST_CONFIG.postgresql.url,
    });
    await this.client.connect();
  }

  async findBySvixId(table: TableName, svixId: string) {
    if (!this.client) {
      throw new Error('Not connected');
    }

    const { rows } = await this.client.query(
      `SELECT * FROM ${table} WHERE svix_id = $1`,
      [svixId],
    );
    return rows[0] || null;
  }

  async countBySvixId(table: TableName, svixId: string): Promise<number> {
    if (!this.client) {
      throw new Error('Not connected');
    }

    const { rows } = await this.client.query(
      `SELECT COUNT(*) as count FROM ${table} WHERE svix_id = $1`,
      [svixId],
    );
    return Number.parseInt(rows[0].count, 10);
  }

  async getUuidVersionBySvixId(
    table: TableName,
    svixId: string,
  ): Promise<number | null> {
    if (!this.client) {
      throw new Error('Not connected');
    }

    const { rows } = await this.client.query(
      `SELECT uuid_extract_version(id) AS uuid_version FROM ${table} WHERE svix_id = $1`,
      [svixId],
    );
    return rows[0]?.uuid_version ?? null;
  }

  async truncate(table: TableName) {
    if (!this.client) {
      throw new Error('Not connected');
    }

    await this.client.query(`TRUNCATE TABLE ${table}`);
  }

  async truncateConversations() {
    if (!this.client) {
      throw new Error('Not connected');
    }
    await this.client.query('TRUNCATE TABLE email_conversations CASCADE');
  }

  async findEmailMessageByResendId(resendEmailId: string) {
    if (!this.client) {
      throw new Error('Not connected');
    }
    const { rows } = await this.client.query(
      'SELECT * FROM email_messages WHERE resend_email_id = $1',
      [resendEmailId],
    );
    return rows[0] || null;
  }

  async getThreadState(childResendEmailId: string) {
    if (!this.client) {
      throw new Error('Not connected');
    }
    const { rows } = await this.client.query(
      `SELECT
         (SELECT COUNT(*)::int FROM email_conversations) AS conversation_count,
         parent.internet_message_id AS parent_internet_message_id
       FROM email_messages child
       LEFT JOIN email_messages parent ON parent.id = child.parent_message_id
       WHERE child.resend_email_id = $1`,
      [childResendEmailId],
    );
    return rows[0] || null;
  }

  async close() {
    if (this.client) {
      await this.client.end();
      this.client = null;
    }
  }
}
