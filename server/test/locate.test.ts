import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { sweepExpiredCommands } from '../src/services/locate.ts';
import {
  bearer,
  createFamily,
  createFakeSocket,
  createTestContext,
  registerDevice,
  registerUser,
  type TestContext,
} from './helpers.ts';

let ctx: TestContext;

beforeAll(async () => {
  ctx = await createTestContext();
});

afterAll(async () => {
  if (ctx) await ctx.cleanup();
});

async function setupDevice(username: string, deviceName = '定位设备') {
  const admin = await registerUser(ctx.app, username);
  await createFamily(ctx.app, admin.token);
  const device = await registerDevice(ctx.app, admin.token, deviceName);
  return { admin, device };
}

describe('locate now', () => {
  it('rejects locating an offline device', async () => {
    const { admin, device } = await setupDevice('locadmin1');

    const response = await ctx.app.inject({
      method: 'POST',
      url: `/api/devices/${device.device.id}/locate`,
      headers: bearer(admin.token),
    });

    expect(response.statusCode).toBe(409);
    expect(response.json()).toMatchObject({ error: { code: 'DEVICE_OFFLINE' } });
  });

  it('dispatches through the device socket, dedupes and completes via ingest', async () => {
    const { admin, device } = await setupDevice('locadmin2');
    await ctx.prisma.device.update({
      where: { id: device.device.id },
      data: { online: true },
    });

    const fakeSocket = createFakeSocket();
    ctx.app.realtime.attach(device.device.id, fakeSocket);

    const first = await ctx.app.inject({
      method: 'POST',
      url: `/api/devices/${device.device.id}/locate`,
      headers: bearer(admin.token),
    });
    expect(first.statusCode).toBe(202);
    const firstBody = first.json<{ commandId: string; deadlineAt: string }>();
    expect(firstBody.commandId).toEqual(expect.any(String));
    expect(Number.isNaN(Date.parse(firstBody.deadlineAt))).toBe(false);

    expect(fakeSocket.sent).toHaveLength(1);
    expect(fakeSocket.sent[0]).toEqual({
      type: 'locate',
      commandId: firstBody.commandId,
      deadlineAt: firstBody.deadlineAt,
    });

    // A second request inside the 30s window returns the same pending command.
    const second = await ctx.app.inject({
      method: 'POST',
      url: `/api/devices/${device.device.id}/locate`,
      headers: bearer(admin.token),
    });
    expect(second.statusCode).toBe(200);
    expect(second.json()).toEqual(firstBody);
    expect(fakeSocket.sent).toHaveLength(1);

    const pending = await ctx.app.inject({
      method: 'GET',
      url: `/api/commands/${firstBody.commandId}`,
      headers: bearer(admin.token),
    });
    expect(pending.statusCode).toBe(200);
    expect(pending.json()).toEqual({ status: 'pending' });

    const ingest = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: {
        commandId: firstBody.commandId,
        points: [
          {
            lat: 30.2741,
            lng: 120.1551,
            accuracy: 8,
            battery: 77,
            provider: 'amap',
            recordedAt: new Date().toISOString(),
          },
        ],
      },
    });
    expect(ingest.statusCode).toBe(202);
    expect(ingest.json()).toEqual({ accepted: 1 });

    const completed = await ctx.app.inject({
      method: 'GET',
      url: `/api/commands/${firstBody.commandId}`,
      headers: bearer(admin.token),
    });
    expect(completed.statusCode).toBe(200);
    const completedBody = completed.json<{ status: string; completedAt?: string }>();
    expect(completedBody.status).toBe('completed');
    expect(typeof completedBody.completedAt).toBe('string');

    const commandRow = await ctx.prisma.locateCommand.findUnique({
      where: { id: firstBody.commandId },
    });
    expect(commandRow?.status).toBe('completed');
    expect(commandRow?.completedAt).toBeInstanceOf(Date);
  });

  it('dedupes concurrent locate requests atomically', async () => {
    const { admin, device } = await setupDevice('locconcurrent4');
    await ctx.prisma.device.update({
      where: { id: device.device.id },
      data: { online: true },
    });

    const fakeSocket = createFakeSocket();
    ctx.app.realtime.attach(device.device.id, fakeSocket);

    const responses = await Promise.all(
      Array.from({ length: 8 }, () =>
        ctx.app.inject({
          method: 'POST',
          url: `/api/devices/${device.device.id}/locate`,
          headers: bearer(admin.token),
        }),
      ),
    );

    const statuses = responses.map((response) => response.statusCode);
    expect(statuses.filter((status) => status === 202)).toHaveLength(1);
    expect(statuses.filter((status) => status === 200)).toHaveLength(7);

    const commandIds = new Set(
      responses.map((response) => response.json<{ commandId: string }>().commandId),
    );
    expect(commandIds.size).toBe(1);
    expect(fakeSocket.sent).toHaveLength(1);
    expect(
      await ctx.prisma.locateCommand.count({
        where: { deviceId: device.device.id, status: 'pending' },
      }),
    ).toBe(1);
  });

  it('does not complete a command with only pre-command points', async () => {
    const { admin, device } = await setupDevice('locadmin5');
    await ctx.prisma.device.update({
      where: { id: device.device.id },
      data: { online: true },
    });

    const fakeSocket = createFakeSocket();
    ctx.app.realtime.attach(device.device.id, fakeSocket);

    const locate = await ctx.app.inject({
      method: 'POST',
      url: `/api/devices/${device.device.id}/locate`,
      headers: bearer(admin.token),
    });
    expect(locate.statusCode).toBe(202);
    const { commandId } = locate.json<{ commandId: string }>();

    const oldPoint = {
      lat: 30.2741,
      lng: 120.1551,
      accuracy: 8,
      provider: 'amap',
      recordedAt: new Date(Date.now() - 60_000).toISOString(),
    };
    const staleBatch = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: { commandId, points: [oldPoint] },
    });
    expect(staleBatch.statusCode).toBe(202);
    expect(staleBatch.json()).toEqual({ accepted: 1 });

    const stillPending = await ctx.app.inject({
      method: 'GET',
      url: `/api/commands/${commandId}`,
      headers: bearer(admin.token),
    });
    expect(stillPending.json()).toEqual({ status: 'pending' });

    const freshBatch = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: {
        commandId,
        points: [{ ...oldPoint, recordedAt: new Date().toISOString() }],
      },
    });
    expect(freshBatch.statusCode).toBe(202);

    const completed = await ctx.app.inject({
      method: 'GET',
      url: `/api/commands/${commandId}`,
      headers: bearer(admin.token),
    });
    expect(completed.json()).toMatchObject({ status: 'completed' });
  });

  it('marks expired pending commands as timeout', async () => {
    const { admin, device } = await setupDevice('locadmin3');
    await ctx.prisma.device.update({
      where: { id: device.device.id },
      data: { online: true },
    });

    const fakeSocket = createFakeSocket();
    ctx.app.realtime.attach(device.device.id, fakeSocket);

    const locate = await ctx.app.inject({
      method: 'POST',
      url: `/api/devices/${device.device.id}/locate`,
      headers: bearer(admin.token),
    });
    expect(locate.statusCode).toBe(202);
    const { commandId } = locate.json<{ commandId: string }>();

    await ctx.prisma.locateCommand.update({
      where: { id: commandId },
      data: { deadlineAt: new Date(Date.now() - 1000) },
    });

    const swept = await sweepExpiredCommands(ctx.prisma);
    expect(swept).toBeGreaterThanOrEqual(1);

    const command = await ctx.app.inject({
      method: 'GET',
      url: `/api/commands/${commandId}`,
      headers: bearer(admin.token),
    });
    expect(command.statusCode).toBe(200);
    expect(command.json()).toEqual({ status: 'timeout' });
  });
});
