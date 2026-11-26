import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  bearer,
  createFamily,
  createTestContext,
  joinFamily,
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

const BASE_TIME = new Date('2026-06-15T08:00:00.000Z');

function point(offsetMinutes: number, overrides: Record<string, unknown> = {}) {
  return {
    lat: 30.2741 + offsetMinutes * 0.0001,
    lng: 120.1551 + offsetMinutes * 0.0001,
    accuracy: 12,
    speed: 3.5,
    bearing: 90,
    battery: 82,
    provider: 'amap',
    recordedAt: new Date(BASE_TIME.getTime() + offsetMinutes * 60_000).toISOString(),
    ...overrides,
  };
}

describe('location ingest and queries', () => {
  it('authenticates ingest with a device token only', async () => {
    const admin = await registerUser(ctx.app, 'locadmin1');
    await createFamily(ctx.app, admin.token);
    const device = await registerDevice(ctx.app, admin.token);

    const noAuth = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      payload: { points: [point(0)] },
    });
    expect(noAuth.statusCode).toBe(401);
    expect(noAuth.json()).toMatchObject({ error: { code: 'UNAUTHORIZED' } });

    const userJwt = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(admin.token),
      payload: { points: [point(0)] },
    });
    expect(userJwt.statusCode).toBe(401);

    const bogus = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer('not-a-real-device-token'),
      payload: { points: [point(0)] },
    });
    expect(bogus.statusCode).toBe(401);

    const accepted = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: { points: [point(0), point(1)] },
    });
    expect(accepted.statusCode).toBe(202);
    expect(accepted.json()).toEqual({ accepted: 2 });

    const rows = await ctx.prisma.location.findMany({
      where: { deviceId: device.device.id },
      orderBy: { recordedAt: 'asc' },
    });
    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({
      userId: admin.user.id,
      familyId: expect.any(String),
      deviceId: device.device.id,
      accuracy: 12,
    });

    const deviceRow = await ctx.prisma.device.findUnique({ where: { id: device.device.id } });
    expect(deviceRow?.lastSeenAt).toBeInstanceOf(Date);

    // Device tokens are stored only as SHA-256 hashes.
    expect(deviceRow?.tokenHash).not.toBe(device.deviceToken);
    expect(deviceRow?.tokenHash).toMatch(/^[0-9a-f]{64}$/);

    // Deleting the device immediately invalidates the token.
    const deleted = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/devices/${device.device.id}`,
      headers: bearer(admin.token),
    });
    expect(deleted.statusCode).toBe(204);

    const afterDelete = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: { points: [point(2)] },
    });
    expect(afterDelete.statusCode).toBe(401);

    const locateRevoked = await ctx.app.inject({
      method: 'POST',
      url: `/api/devices/${device.device.id}/locate`,
      headers: bearer(admin.token),
    });
    expect(locateRevoked.statusCode).toBe(404);

    const renameRevoked = await ctx.app.inject({
      method: 'PATCH',
      url: `/api/devices/${device.device.id}`,
      headers: bearer(admin.token),
      payload: { name: '已解绑设备' },
    });
    expect(renameRevoked.statusCode).toBe(404);

    // Soft unbind keeps the row and history, but hides the device.
    const revokedRow = await ctx.prisma.device.findUnique({ where: { id: device.device.id } });
    expect(revokedRow?.active).toBe(false);
    expect(revokedRow?.revokedAt).toBeInstanceOf(Date);
    expect(await ctx.prisma.location.count({ where: { deviceId: device.device.id } })).toBe(2);

    const devices = await ctx.app.inject({
      method: 'GET',
      url: '/api/devices',
      headers: bearer(admin.token),
    });
    expect(devices.statusCode).toBe(200);
    expect(devices.json()).toEqual({ devices: [] });

    const me = await ctx.app.inject({
      method: 'GET',
      url: '/api/me',
      headers: bearer(admin.token),
    });
    expect(me.json()).toMatchObject({ devices: [] });

    const from = new Date(BASE_TIME.getTime() - 60_000).toISOString();
    const to = new Date(BASE_TIME.getTime() + 10 * 60_000).toISOString();
    const retained = await ctx.app.inject({
      method: 'GET',
      url: `/api/devices/${device.device.id}/locations?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
      headers: bearer(admin.token),
    });
    expect(retained.statusCode).toBe(200);
    expect(retained.json<{ points: unknown[] }>().points).toHaveLength(2);

    const latest = await ctx.app.inject({
      method: 'GET',
      url: '/api/families/me/latest',
      headers: bearer(admin.token),
    });
    const adminEntry = latest
      .json<{ members: Array<{ userId: string; deviceId: string | null; lastLocation: unknown }> }>()
      .members.find((entry) => entry.userId === admin.user.id);
    expect(adminEntry).toMatchObject({ deviceId: null, lastLocation: null });
  });

  it('rejects recordedAt values too far in the future', async () => {
    const admin = await registerUser(ctx.app, 'locadmin7');
    await createFamily(ctx.app, admin.token);
    const device = await registerDevice(ctx.app, admin.token);

    const tooFar = new Date(Date.now() + 6 * 60_000).toISOString();
    const rejected = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: { points: [point(0, { recordedAt: tooFar })] },
    });
    expect(rejected.statusCode).toBe(400);
    expect(rejected.json()).toMatchObject({ error: { code: 'VALIDATION' } });
    expect(await ctx.prisma.location.count({ where: { deviceId: device.device.id } })).toBe(0);

    const nearFuture = new Date(Date.now() + 4 * 60_000).toISOString();
    const accepted = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: { points: [point(0, { recordedAt: nearFuture })] },
    });
    expect(accepted.statusCode).toBe(202);
    expect(await ctx.prisma.location.count({ where: { deviceId: device.device.id } })).toBe(1);
  });

  it('validates batch size and point fields', async () => {
    const admin = await registerUser(ctx.app, 'locadmin2');
    await createFamily(ctx.app, admin.token);
    const device = await registerDevice(ctx.app, admin.token);
    const headers = bearer(device.deviceToken);

    const empty = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers,
      payload: { points: [] },
    });
    expect(empty.statusCode).toBe(400);
    expect(empty.json()).toMatchObject({ error: { code: 'VALIDATION' } });

    const tooMany = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers,
      payload: { points: Array.from({ length: 101 }, (_, index) => point(index)) },
    });
    expect(tooMany.statusCode).toBe(400);
    expect(tooMany.json()).toMatchObject({ error: { code: 'VALIDATION' } });

    const badLat = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers,
      payload: { points: [point(0, { lat: 91 })] },
    });
    expect(badLat.statusCode).toBe(400);

    const badBattery = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers,
      payload: { points: [point(0, { battery: 120 })] },
    });
    expect(badBattery.statusCode).toBe(400);

    const badTime = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers,
      payload: { points: [point(0, { recordedAt: 'not-a-date' })] },
    });
    expect(badTime.statusCode).toBe(400);

    // Exactly 100 points is allowed.
    const maxBatch = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers,
      payload: { points: Array.from({ length: 100 }, (_, index) => point(index)) },
    });
    expect(maxBatch.statusCode).toBe(202);
    expect(maxBatch.json()).toEqual({ accepted: 100 });
  });

  it('returns family-scoped history with dates, ordering and limits', async () => {
    const admin = await registerUser(ctx.app, 'locadmin3');
    const family = await createFamily(ctx.app, admin.token);
    const member = await registerUser(ctx.app, 'locmember3');
    await joinFamily(ctx.app, member.token, family.inviteCode!);
    const device = await registerDevice(ctx.app, admin.token);

    const ingest = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: { points: [point(0), point(1), point(2)] },
    });
    expect(ingest.statusCode).toBe(202);

    const from = new Date(BASE_TIME.getTime() - 60_000).toISOString();
    const to = new Date(BASE_TIME.getTime() + 10 * 60_000).toISOString();

    const history = await ctx.app.inject({
      method: 'GET',
      url: `/api/devices/${device.device.id}/locations?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&limit=2`,
      headers: bearer(member.token),
    });
    expect(history.statusCode).toBe(200);
    const body = history.json<{ points: Array<Record<string, unknown>> }>();
    expect(body.points).toHaveLength(2);
    expect(body.points[0]).toMatchObject({
      lat: 30.2741,
      lng: 120.1551,
      accuracy: 12,
      speed: 3.5,
      bearing: 90,
      battery: 82,
      provider: 'amap',
    });
    expect(typeof body.points[0]!.recordedAt).toBe('string');

    const ascending = await ctx.app.inject({
      method: 'GET',
      url: `/api/devices/${device.device.id}/locations?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
      headers: bearer(admin.token),
    });
    const ascendingBody = ascending.json<{ points: Array<{ recordedAt: string }> }>();
    expect(ascendingBody.points.map((entry) => entry.recordedAt)).toEqual([
      point(0).recordedAt,
      point(1).recordedAt,
      point(2).recordedAt,
    ]);

    const invalidRange = await ctx.app.inject({
      method: 'GET',
      url: `/api/devices/${device.device.id}/locations?from=${encodeURIComponent(to)}&to=${encodeURIComponent(from)}`,
      headers: bearer(admin.token),
    });
    expect(invalidRange.statusCode).toBe(400);
    expect(invalidRange.json()).toMatchObject({ error: { code: 'VALIDATION' } });

    const tooLargeLimit = await ctx.app.inject({
      method: 'GET',
      url: `/api/devices/${device.device.id}/locations?limit=5001`,
      headers: bearer(admin.token),
    });
    expect(tooLargeLimit.statusCode).toBe(400);
  });

  it('uses today and now as the default history range', async () => {
    const admin = await registerUser(ctx.app, 'locadmin4');
    await createFamily(ctx.app, admin.token);
    const device = await registerDevice(ctx.app, admin.token);

    const now = new Date();
    await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: {
        points: [
          {
            lat: 30.2741,
            lng: 120.1551,
            accuracy: 15,
            recordedAt: now.toISOString(),
          },
        ],
      },
    });

    const history = await ctx.app.inject({
      method: 'GET',
      url: `/api/devices/${device.device.id}/locations`,
      headers: bearer(admin.token),
    });
    expect(history.statusCode).toBe(200);
    expect(history.json<{ points: unknown[] }>().points).toHaveLength(1);
  });

  it('returns latest positions for the family map', async () => {
    const admin = await registerUser(ctx.app, 'locadmin5');
    const family = await createFamily(ctx.app, admin.token);
    const member = await registerUser(ctx.app, 'locmember5');
    await joinFamily(ctx.app, member.token, family.inviteCode!);
    const device = await registerDevice(ctx.app, admin.token, '管理员手机');

    const latestPoint = point(5);
    await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: { points: [point(0), latestPoint] },
    });

    const latest = await ctx.app.inject({
      method: 'GET',
      url: '/api/families/me/latest',
      headers: bearer(admin.token),
    });
    expect(latest.statusCode).toBe(200);
    const body = latest.json<{
      members: Array<{
        userId: string;
        deviceId: string | null;
        online: boolean;
        lastSeenAt: string | null;
        lastLocation: Record<string, unknown> | null;
      }>;
    }>();
    expect(body.members).toHaveLength(2);

    const adminEntry = body.members.find((entry) => entry.userId === admin.user.id);
    expect(adminEntry).toMatchObject({
      deviceId: device.device.id,
      online: false,
      lastLocation: {
        lat: latestPoint.lat,
        lng: latestPoint.lng,
        accuracy: 12,
        battery: 82,
        recordedAt: latestPoint.recordedAt,
      },
    });
    expect(typeof adminEntry?.lastSeenAt).toBe('string');

    const memberEntry = body.members.find((entry) => entry.userId === member.user.id);
    expect(memberEntry).toMatchObject({ deviceId: null, lastLocation: null });

    const overview = await ctx.app.inject({
      method: 'GET',
      url: '/api/families/me',
      headers: bearer(member.token),
    });
    const overviewBody = overview.json<{
      members: Array<{ userId: string; lastLocation: Record<string, unknown> | null }>;
    }>();
    const overviewAdmin = overviewBody.members.find((entry) => entry.userId === admin.user.id);
    expect(overviewAdmin?.lastLocation).toMatchObject({ lat: latestPoint.lat, lng: latestPoint.lng });
  });

  it('returns devices of the whole family with owner and last location', async () => {
    const admin = await registerUser(ctx.app, 'locadmin6');
    const family = await createFamily(ctx.app, admin.token);
    const member = await registerUser(ctx.app, 'locmember6');
    await joinFamily(ctx.app, member.token, family.inviteCode!);
    const adminDevice = await registerDevice(ctx.app, admin.token, '管理员手机');
    const memberDevice = await registerDevice(ctx.app, member.token, '成员手机');

    await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(adminDevice.deviceToken),
      payload: { points: [point(0)] },
    });

    const response = await ctx.app.inject({
      method: 'GET',
      url: '/api/devices',
      headers: bearer(member.token),
    });
    expect(response.statusCode).toBe(200);
    const body = response.json<{
      devices: Array<{ id: string; userId: string; name: string; lastLocation: unknown }>;
    }>();
    expect(body.devices).toHaveLength(2);
    expect(body.devices.map((device) => device.id).sort()).toEqual(
      [adminDevice.device.id, memberDevice.device.id].sort(),
    );
    const adminEntry = body.devices.find((device) => device.id === adminDevice.device.id);
    expect(adminEntry).toMatchObject({
      userId: admin.user.id,
      name: '管理员手机',
      lastLocation: { lat: 30.2741, lng: 120.1551 },
    });
  });
});
