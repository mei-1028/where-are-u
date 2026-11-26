import { PrismaClient } from '@prisma/client';

/**
 * Creates a Prisma client bound to an explicit SQLite URL. Passing the URL
 * explicitly keeps tests isolated and avoids relying on process.env lookups.
 */
export function createPrismaClient(databaseUrl: string): PrismaClient {
  return new PrismaClient({
    datasources: {
      db: { url: databaseUrl },
    },
  });
}

export function isUniqueConstraintError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'code' in error &&
    (error as { code?: unknown }).code === 'P2002'
  );
}
