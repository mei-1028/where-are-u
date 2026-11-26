import { execFileSync } from 'node:child_process';
import { readdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { FastifyInstance } from 'fastify';
import { buildServer, type BuildServerOptions } from '../src/app.ts';
import type { PrismaClient } from '@prisma/client';

const testDir = dirname(fileURLToPath(import.meta.url));
const serverRoot = resolve(testDir, '..');
const prismaDir = join(serverRoot, 'prisma');
const prismaCli = join(serverRoot, 'node_modules', 'prisma', 'build', 'index.js');

// Deterministic, test-only signing secret (never used outside tests).
// Must satisfy the production config rules: >= 32 bytes, not a placeholder.
const TEST_JWT_SECRET = 'server-test-only-jwt-signing-key-0123456789abcdef';

export const TEST_PASSWORD = 'password123';

let databaseSequence = 0;

export interface TestContext {
  app: FastifyInstance;
  prisma: PrismaClient;
  databaseUrl: string;
  cleanup: () => Promise<void>;
}

export interface TestContextOptions {
  heartbeatIntervalMs?: number;
  wsSilenceTimeoutMs?: number;
  sweepIntervalMs?: number;
}

export async function createTestContext(options: TestContextOptions = {}): Promise<TestContext> {
  databaseSequence += 1;
  const dbName = `.test-${process.pid}-${Date.now()}-${databaseSequence}.db`;
  const databaseUrl = `file:./${dbName}`;

  // Push the schema into a throwaway SQLite file next to schema.prisma.
  execFileSync(process.execPath, [prismaCli, 'db', 'push', '--skip-generate'], {
    cwd: serverRoot,
    env: { ...process.env, DATABASE_URL: databaseUrl },
    stdio: 'pipe',
  });

  const buildOptions: BuildServerOptions = {
    config: {
      databaseUrl,
      jwtSecret: TEST_JWT_SECRET,
      corsOrigin: '*',
      locateTimeoutMs: 30_000,
      logLevel: 'silent',
    },
    logger: false,
    ...(options.heartbeatIntervalMs !== undefined
      ? { heartbeatIntervalMs: options.heartbeatIntervalMs }
      : {}),
    ...(options.wsSilenceTimeoutMs !== undefined
      ? { wsSilenceTimeoutMs: options.wsSilenceTimeoutMs }
      : {}),
    ...(options.sweepIntervalMs !== undefined ? { sweepIntervalMs: options.sweepIntervalMs } : {}),
  };

  const app = await buildServer(buildOptions);
  await app.ready();

  return {
    app,
    prisma: app.prisma,
    databaseUrl,
    cleanup: async () => {
      await app.close().catch(() => undefined);
      for (const file of readdirSync(prismaDir)) {
        if (file.startsWith(dbName)) {
          rmSync(join(prismaDir, file), { force: true });
        }
      }
    },
  };
}

export function bearer(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}` };
}

export interface AuthPayload {
  token: string;
  user: { id: string; username: string; nickname: string };
  familyId: string | null;
}

export async function registerUser(
  app: FastifyInstance,
  username: string,
  extra: Record<string, unknown> = {},
): Promise<AuthPayload> {
  const response = await app.inject({
    method: 'POST',
    url: '/api/auth/register',
    payload: {
      username,
      nickname: username,
      password: TEST_PASSWORD,
      ...extra,
    },
  });

  if (response.statusCode !== 201) {
    throw new Error(`registerUser(${username}) failed: ${response.statusCode} ${response.body}`);
  }
  return response.json<AuthPayload>();
}

export async function loginUser(
  app: FastifyInstance,
  username: string,
  password = TEST_PASSWORD,
): Promise<AuthPayload> {
  const response = await app.inject({
    method: 'POST',
    url: '/api/auth/login',
    payload: { username, password },
  });

  if (response.statusCode !== 200) {
    throw new Error(`loginUser(${username}) failed: ${response.statusCode} ${response.body}`);
  }
  return response.json<AuthPayload>();
}

export interface FamilyPayload {
  id: string;
  name: string;
  inviteCode?: string;
  inviteExpiresAt?: string;
  role: 'admin' | 'member';
}

export async function createFamily(
  app: FastifyInstance,
  token: string,
  name = '测试家庭',
): Promise<FamilyPayload> {
  const response = await app.inject({
    method: 'POST',
    url: '/api/families',
    headers: bearer(token),
    payload: { name },
  });
  if (response.statusCode !== 201) {
    throw new Error(`createFamily failed: ${response.statusCode} ${response.body}`);
  }
  return response.json<{ family: FamilyPayload }>().family;
}

export async function joinFamily(
  app: FastifyInstance,
  token: string,
  code: string,
): Promise<FamilyPayload> {
  const response = await app.inject({
    method: 'POST',
    url: '/api/families/join',
    headers: bearer(token),
    payload: { code },
  });
  if (response.statusCode !== 200) {
    throw new Error(`joinFamily failed: ${response.statusCode} ${response.body}`);
  }
  return response.json<{ family: FamilyPayload }>().family;
}

export interface DevicePayload {
  device: {
    id: string;
    userId: string;
    name: string;
    platform: string;
    model: string;
    osVersion: string;
    appVersion: string;
    online: boolean;
    lastSeenAt: string | null;
    lastLocation: unknown;
  };
  deviceToken: string;
}

export async function registerDevice(
  app: FastifyInstance,
  token: string,
  name = '测试设备',
): Promise<DevicePayload> {
  const response = await app.inject({
    method: 'POST',
    url: '/api/devices',
    headers: bearer(token),
    payload: {
      name,
      platform: 'android',
      model: 'Pixel 8',
      osVersion: 'Android 15',
      appVersion: '0.1.0',
    },
  });
  if (response.statusCode !== 201) {
    throw new Error(`registerDevice failed: ${response.statusCode} ${response.body}`);
  }
  return response.json<DevicePayload>();
}

export interface FakeSocket {
  readyState: number;
  sent: unknown[];
  send: (data: string) => void;
  close: (code?: number, reason?: string) => void;
}

export function createFakeSocket(): FakeSocket {
  const socket: FakeSocket = {
    readyState: 1,
    sent: [],
    send(data: string) {
      socket.sent.push(JSON.parse(data) as unknown);
    },
    close() {
      socket.readyState = 3;
    },
  };
  return socket;
}

/** A deterministic 6-digit code that is guaranteed not to exist in the DB. */
export async function unusedInviteCode(prisma: PrismaClient): Promise<string> {
  const families = await prisma.family.findMany({ select: { inviteCode: true } });
  const used = new Set(families.map((family) => family.inviteCode));
  for (let value = 0; value < 1_000_000; value += 1) {
    const code = value.toString().padStart(6, '0');
    if (!used.has(code)) return code;
  }
  throw new Error('No unused invite code available');
}

export async function waitFor<T>(
  probe: () => Promise<T | null | undefined | false>,
  options: { timeoutMs?: number; intervalMs?: number; label?: string } = {},
): Promise<T> {
  const timeoutMs = options.timeoutMs ?? 5000;
  const intervalMs = options.intervalMs ?? 25;
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    const value = await probe();
    if (value) return value;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, intervalMs));
  }
  throw new Error(`waitFor timed out${options.label ? `: ${options.label}` : ''}`);
}
