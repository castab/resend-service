import fs from 'node:fs';
import path from 'node:path';
import { config } from 'dotenv';
import { Client } from 'pg';

config({ path: path.resolve(__dirname, '../.env.test') });

async function main() {
  console.info('Setting up PostgreSQL...');

  const connectionString = process.env.POSTGRESQL_URL;
  if (!connectionString) {
    throw new Error('Missing POSTGRESQL_URL environment variable');
  }

  const client = new Client({ connectionString });
  await client.connect();

  try {
    const schema = fs.readFileSync(
      path.resolve(__dirname, '../schemas/postgresql.sql'),
      'utf-8',
    );
    await client.query(schema);
  } finally {
    await client.end();
  }

  console.info('PostgreSQL setup complete');
}

main().catch((error) => {
  console.error('Setup failed:', error);
  process.exit(1);
});
