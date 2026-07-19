import { Client } from 'pg';
import { TEST_CONFIG } from '../setup';

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

  async close() {
    if (this.client) {
      await this.client.end();
      this.client = null;
    }
  }
}
