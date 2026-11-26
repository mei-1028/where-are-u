import { once } from 'node:events';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import {
  bearer,
  createFamily,
  createTestContext,
  registerDevice,
  registerUser,
  waitFor,
  type AuthPayload,
  type DevicePayload,
  type TestContext,
} from './helpers.ts';

let ctx: TestContext;
let wsUrl: string;
let device: DevicePayload;
let adminSession: AuthPayload;

beforeAll(async () => {
  ctx = await createTestContext({
    heartbeatIntervalMs: 50,
    wsSilenceTimeoutMs: 250,
  });
  await ctx.app.listen({ port: 0, host: '127.0.0.1' });
  const address = ctx.app.server.address();
  if (!address || typeof address === 'string') throw new Error('Failed to determine test port');
  wsUrl = `ws://127.0.0.1:${address.port}/ws`;

  adminSession = await registerUser(ctx.app, 'wsuser01');
  await createFamily(ctx.app, adminSession.token, 'WS 家庭');
  device = await registerDevice(ctx.app, adminSession.token, 'WS 设备');
});

afterAll(async () => {
  if (ctx) await ctx.cleanup();
});

function openSocket(token: string): WebSocket {
  const socket = new WebSocket(wsUrl, { headers: bearer(token) });
  socket.on('error', () => undefined);
  return socket;
}

function upgradeRejection(token: string): Promise<{ statusCode: number; body: string }> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(wsUrl, { headers: bearer(token) });
    const timer = setTimeout(() => {
      socket.terminate();
      reject(new Error('Timed out waiting for upgrade rejection'));
    }, 5000);

    socket.on('unexpected-response', (_request, response) => {
      const chunks: Buffer[] = [];
      response.on('data', (chunk: Buffer) => chunks.push(chunk));
      response.on('end', () => {
        clearTimeout(timer);
        socket.terminate();
        resolve({
          statusCode: response.statusCode ?? 0,
          body: Buffer.concat(chunks).toString('utf8'),
        });
      });
    });

    socket.on('open', () => {
      clearTimeout(timer);
      socket.terminate();
      reject(new Error('Upgrade unexpectedly succeeded'));
    });

    socket.on('error', () => {
      // A failed upgrade intentionally produces this; the response is handled above.
    });
  });
}

describe('device WebSocket', () => {
  it('rejects an invalid device token before upgrading', async () => {
    const rejection = await upgradeRejection('invalid-device-token');

    expect(rejection.statusCode).toBe(401);
    const body = JSON.parse(rejection.body) as { error: { code: string } };
    expect(body.error.code).toBe('UNAUTHORIZED');
  });

  it('rejects a revoked device token before upgrading', async () => {
    const extra = await registerDevice(ctx.app, adminSession.token, '待解绑设备');
    const deleted = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/devices/${extra.device.id}`,
      headers: bearer(adminSession.token),
    });
    expect(deleted.statusCode).toBe(204);

    const rejection = await upgradeRejection(extra.deviceToken);
    expect(rejection.statusCode).toBe(401);
    expect((JSON.parse(rejection.body) as { error: { code: string } }).error.code).toBe(
      'UNAUTHORIZED',
    );
  });

  it('tracks online presence and accepts hello/pong messages', async () => {
    const socket = openSocket(device.deviceToken);
    await once(socket, 'open');

    await waitFor(
      async () => (await ctx.prisma.device.findUnique({ where: { id: device.device.id } }))?.online === true,
      { label: 'device becomes online' },
    );

    socket.send(JSON.stringify({ type: 'hello', appVersion: '9.9.9', battery: 88 }));
    await waitFor(
      async () =>
        (await ctx.prisma.device.findUnique({ where: { id: device.device.id } }))?.appVersion === '9.9.9',
      { label: 'hello updates appVersion' },
    );

    socket.send(JSON.stringify({ type: 'pong', ts: new Date().toISOString() }));

    const closed = once(socket, 'close');
    socket.close();
    await closed;

    await waitFor(
      async () => (await ctx.prisma.device.findUnique({ where: { id: device.device.id } }))?.online === false,
      { label: 'device becomes offline' },
    );

    const row = await ctx.prisma.device.findUnique({ where: { id: device.device.id } });
    expect(row?.lastSeenAt).toBeInstanceOf(Date);
  });

  it('pings the device and closes a silent connection', async () => {
    const socket = openSocket(device.deviceToken);
    const messages: Array<Record<string, unknown>> = [];
    socket.on('message', (raw) => {
      messages.push(JSON.parse(raw.toString()) as Record<string, unknown>);
    });

    await once(socket, 'open');

    const closeCode = await new Promise<number>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Socket was not closed for silence')), 5000);
      socket.on('close', (code) => {
        clearTimeout(timer);
        resolve(code);
      });
    });

    expect(closeCode).toBe(4002);
    expect(messages.some((message) => message.type === 'ping')).toBe(true);

    await waitFor(
      async () => (await ctx.prisma.device.findUnique({ where: { id: device.device.id } }))?.online === false,
      { label: 'device offline after heartbeat timeout' },
    );
  });
});
