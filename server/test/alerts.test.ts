import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  evaluateBatteryPoint,
  evaluateMovementPoint,
  haversineMeters,
  isQuietHours,
  sweepAlerts,
} from '../src/services/alerts.ts';
import {
  bearer,
  createFakeSocket,
  createTestContext,
  registerDevice,
  type TestContext,
} from './helpers.ts';

let ctx: TestContext;
let sequence = 0;

beforeAll(async () => {
  ctx = await createTestContext();
});

afterAll(async () => {
  if (ctx) await ctx.cleanup();
});

/**
 * Seeds users directly instead of using /api/auth/register: the auth routes are
 * intentionally rate-limited, and this suite needs a fresh family per scenario.
 */
async function seedUser(username: string) {
  const user = await ctx.prisma.user.create({
    data: { username, nickname: username, passwordHash: 'not-used-in-tests' },
  });
  const token = ctx.app.jwt.sign({ sub: user.id });
  return { user, token };
}

async function seedFamily(adminUserId: string, name = '提醒家庭') {
  sequence += 1;
  return ctx.prisma.family.create({
    data: {
      name,
      inviteCode: `${100000 + sequence}`,
      inviteExpiresAt: new Date(Date.now() + 86_400_000),
    },
  });
}

/** A family with an admin (the follower) and a member (the target). */
async function setupPair() {
  sequence += 1;
  const suffix = `${sequence}`;
  const admin = await seedUser(`alertadmin${suffix}`);
  const member = await seedUser(`alertmember${suffix}`);
  const family = await seedFamily(admin.user.id, `提醒家庭${suffix}`);
  await ctx.prisma.familyMember.createMany({
    data: [
      { familyId: family.id, userId: admin.user.id, role: 'admin' },
      { familyId: family.id, userId: member.user.id, role: 'member' },
    ],
  });
  const adminDevice = await registerDevice(ctx.app, admin.token, '关注者手机');
  const memberDevice = await registerDevice(ctx.app, member.token, '被关注者手机');
  return { admin, member, adminDevice, memberDevice, family };
}

async function createFollow(
  token: string,
  targetUserId: string,
  rules: Array<{ type: string; config?: Record<string, unknown> }>,
) {
  const response = await ctx.app.inject({
    method: 'POST',
    url: '/api/follows',
    headers: bearer(token),
    payload: { targetUserId, rules },
  });
  return response;
}

function isoMinutesAgo(minutes: number): string {
  return new Date(Date.now() - minutes * 60_000).toISOString();
}

async function postPoints(
  deviceToken: string,
  points: Array<Record<string, unknown>>,
) {
  return ctx.app.inject({
    method: 'POST',
    url: '/api/locations',
    headers: bearer(deviceToken),
    payload: { points },
  });
}

describe('follows', () => {
  it('creates a follow with rule defaults, and is idempotent', async () => {
    const { admin, member } = await setupPair();

    const created = await createFollow(admin.token, member.user.id, [
      { type: 'MOVEMENT' },
      { type: 'STALE', config: { hours: 24 } },
    ]);
    expect(created.statusCode).toBe(201);
    const body = created.json<{
      follow: { id: string; target: { nickname: string }; rules: Array<Record<string, unknown>> };
    }>();
    expect(body.follow.target.nickname).toBe(member.user.nickname);
    expect(body.follow.rules).toHaveLength(2);
    const movement = body.follow.rules.find((rule) => rule.type === 'MOVEMENT');
    expect(movement?.config).toEqual({ thresholdMeters: 500, cooldownMinutes: 30 });

    const again = await createFollow(admin.token, member.user.id, []);
    expect(again.statusCode).toBe(200);
    expect(again.json<{ follow: { id: string } }>().follow.id).toBe(body.follow.id);

    const list = await ctx.app.inject({
      method: 'GET',
      url: '/api/follows',
      headers: bearer(admin.token),
    });
    expect(list.statusCode).toBe(200);
    expect(list.json<{ follows: unknown[] }>().follows).toHaveLength(1);
  });

  it('rejects following yourself and users outside the family', async () => {
    const { admin } = await setupPair();
    const self = await createFollow(admin.token, admin.user.id, []);
    expect(self.statusCode).toBe(400);

    sequence += 1;
    const outsider = await seedUser(`alertoutsider${sequence}`);
    const outsiderFamily = await seedFamily(outsider.user.id, `外部家庭${sequence}`);
    await ctx.prisma.familyMember.create({
      data: { familyId: outsiderFamily.id, userId: outsider.user.id, role: 'admin' },
    });
    const cross = await createFollow(admin.token, outsider.user.id, []);
    expect(cross.statusCode).toBe(404);
  });

  it('lets the target see who follows them', async () => {
    const { admin, member } = await setupPair();
    await createFollow(admin.token, member.user.id, []);

    const followers = await ctx.app.inject({
      method: 'GET',
      url: '/api/follows/followers',
      headers: bearer(member.token),
    });
    expect(followers.statusCode).toBe(200);
    const body = followers.json<{ followers: Array<{ userId: string; nickname: string }> }>();
    expect(body.followers).toHaveLength(1);
    expect(body.followers[0]!.userId).toBe(admin.user.id);
  });

  it('updates and deletes rules, rejecting duplicate types', async () => {
    const { admin, member } = await setupPair();
    const created = await createFollow(admin.token, member.user.id, [{ type: 'MOVEMENT' }]);
    const follow = created.json<{ follow: { id: string; rules: Array<{ id: string }> } }>().follow;
    const ruleId = follow.rules[0]!.id;

    const duplicate = await ctx.app.inject({
      method: 'POST',
      url: `/api/follows/${follow.id}/rules`,
      headers: bearer(admin.token),
      payload: { type: 'MOVEMENT' },
    });
    expect(duplicate.statusCode).toBe(409);

    const patched = await ctx.app.inject({
      method: 'PATCH',
      url: `/api/follows/${follow.id}/rules/${ruleId}`,
      headers: bearer(admin.token),
      payload: { enabled: false, config: { thresholdMeters: 800 } },
    });
    expect(patched.statusCode).toBe(200);
    expect(patched.json<{ rule: Record<string, unknown> }>().rule).toMatchObject({
      enabled: false,
      config: { thresholdMeters: 800, cooldownMinutes: 30 },
    });

    const removed = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/follows/${follow.id}/rules/${ruleId}`,
      headers: bearer(admin.token),
    });
    expect(removed.statusCode).toBe(204);
  });
});

describe('movement alerts', () => {
  it('fires once the drifting baseline is exceeded, then respects the cooldown', async () => {
    const { admin, member, adminDevice, memberDevice } = await setupPair();
    await createFollow(admin.token, member.user.id, [
      { type: 'MOVEMENT', config: { thresholdMeters: 200, cooldownMinutes: 30 } },
    ]);

    const socket = createFakeSocket();
    ctx.app.realtime.attach(adminDevice.device.id, socket);

    // First point only seeds the anchor.
    await postPoints(memberDevice.deviceToken, [
      { lat: 31.6, lng: 120.7, accuracy: 10, recordedAt: isoMinutesAgo(3) },
    ]);
    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(0);

    // ~333m north with a good accuracy -> fires.
    await postPoints(memberDevice.deviceToken, [
      { lat: 31.603, lng: 120.7, accuracy: 10, recordedAt: isoMinutesAgo(2) },
    ]);
    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(1);
    expect(socket.sent).toHaveLength(1);
    expect(socket.sent[0]).toMatchObject({
      type: 'alert',
      alert: { type: 'MOVEMENT', silent: false },
    });

    // A second large jump immediately afterwards is inside the cooldown.
    await postPoints(memberDevice.deviceToken, [
      { lat: 31.61, lng: 120.7, accuracy: 10, recordedAt: isoMinutesAgo(1) },
    ]);
    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(1);

    const inbox = await ctx.app.inject({
      method: 'GET',
      url: '/api/alerts',
      headers: bearer(admin.token),
    });
    const body = inbox.json<{
      alerts: Array<{ id: string; data: { distanceMeters: number } }>;
      unreadCount: number;
    }>();
    expect(body.unreadCount).toBe(1);
    expect(body.alerts[0]!.data.distanceMeters).toBeGreaterThan(300);

    const read = await ctx.app.inject({
      method: 'POST',
      url: `/api/alerts/${body.alerts[0]!.id}/read`,
      headers: bearer(admin.token),
    });
    expect(read.statusCode).toBe(204);

    const after = await ctx.app.inject({
      method: 'GET',
      url: '/api/alerts?unread=1',
      headers: bearer(admin.token),
    });
    expect(after.json<{ unreadCount: number }>().unreadCount).toBe(0);
  });

  it('ignores inaccurate fixes that would otherwise look like movement', async () => {
    const { admin, member, memberDevice } = await setupPair();
    await createFollow(admin.token, member.user.id, [
      { type: 'MOVEMENT', config: { thresholdMeters: 200, cooldownMinutes: 30 } },
    ]);

    await postPoints(memberDevice.deviceToken, [
      { lat: 31.6, lng: 120.7, accuracy: 10, recordedAt: isoMinutesAgo(3) },
    ]);
    await postPoints(memberDevice.deviceToken, [
      { lat: 31.603, lng: 120.7, accuracy: 400, recordedAt: isoMinutesAgo(2) },
    ]);

    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(0);
  });

  it('does not evaluate while the target has paused sharing, and re-anchors on resume', async () => {
    const { admin, member, memberDevice } = await setupPair();
    await createFollow(admin.token, member.user.id, [
      { type: 'MOVEMENT', config: { thresholdMeters: 200, cooldownMinutes: 30 } },
    ]);

    await postPoints(memberDevice.deviceToken, [
      { lat: 31.6, lng: 120.7, accuracy: 10, recordedAt: isoMinutesAgo(3) },
    ]);

    const pause = await ctx.app.inject({
      method: 'PATCH',
      url: '/api/families/me/sharing',
      headers: bearer(member.token),
      payload: { paused: true },
    });
    expect(pause.statusCode).toBe(200);

    await postPoints(memberDevice.deviceToken, [
      { lat: 31.62, lng: 120.7, accuracy: 10, recordedAt: isoMinutesAgo(2) },
    ]);
    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(0);

    const resume = await ctx.app.inject({
      method: 'PATCH',
      url: '/api/families/me/sharing',
      headers: bearer(member.token),
      payload: { paused: false },
    });
    expect(resume.statusCode).toBe(200);

    // The first point after resume is a new baseline, not a 2km jump.
    await postPoints(memberDevice.deviceToken, [
      { lat: 31.63, lng: 120.7, accuracy: 10, recordedAt: isoMinutesAgo(1) },
    ]);
    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(0);
  });
});

describe('battery alerts', () => {
  it('fires at the threshold and uses hysteresis before re-arming', async () => {
    const { admin, member, memberDevice } = await setupPair();
    await createFollow(admin.token, member.user.id, [
      { type: 'LOW_BATTERY', config: { percent: 20 } },
    ]);

    await postPoints(memberDevice.deviceToken, [
      { lat: 31.6, lng: 120.7, accuracy: 10, battery: 15, recordedAt: isoMinutesAgo(4) },
    ]);
    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(1);

    // Still low but already fired: no repeat.
    await postPoints(memberDevice.deviceToken, [
      { lat: 31.6, lng: 120.7, accuracy: 10, battery: 12, recordedAt: isoMinutesAgo(3) },
    ]);
    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(1);

    // Charging to 30% (threshold + 5) re-arms.
    await postPoints(memberDevice.deviceToken, [
      { lat: 31.6, lng: 120.7, accuracy: 10, battery: 30, recordedAt: isoMinutesAgo(2) },
    ]);
    // 24% is above the threshold but below the re-arm band; the rule stays armed.
    await postPoints(memberDevice.deviceToken, [
      { lat: 31.6, lng: 120.7, accuracy: 10, battery: 24, recordedAt: isoMinutesAgo(1) },
    ]);
    await postPoints(memberDevice.deviceToken, [
      { lat: 31.6, lng: 120.7, accuracy: 10, battery: 10, recordedAt: isoMinutesAgo(0) },
    ]);
    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(2);
  });

  it('marks a night-time low-battery alert silent', () => {
    // 2026-11-27T18:30:00Z = 02:30 in China.
    const night = new Date('2026-11-27T18:30:00.000Z');
    const result = evaluateBatteryPoint(
      { percent: 20 },
      {},
      'r1',
      '妈妈',
      { lat: 31.6, lng: 120.7, accuracy: 10, battery: 8, recordedAt: night },
      night,
    );
    expect(result.draft?.silent).toBe(true);
    expect(isQuietHours(new Date('2026-11-27T15:00:00.000Z'))).toBe(true);
    expect(isQuietHours(new Date('2026-11-27T14:59:00.000Z'))).toBe(false);
    expect(isQuietHours(new Date('2026-11-27T22:59:00.000Z'))).toBe(true);
    expect(isQuietHours(new Date('2026-11-27T23:00:00.000Z'))).toBe(false);
  });
});

describe('stale alerts', () => {
  it('arms on a fresh point, fires once when overdue, and does not repeat', async () => {
    const { admin, member, memberDevice, family } = await setupPair();
    await createFollow(admin.token, member.user.id, [
      { type: 'STALE', config: { hours: 12 } },
    ]);

    // Following somebody who is already offline must not fire immediately.
    await ctx.prisma.location.create({
      data: {
        deviceId: memberDevice.device.id,
        userId: member.user.id,
        familyId: family.id,
        lat: 31.6,
        lng: 120.7,
        accuracy: 10,
        recordedAt: new Date(Date.now() - 13 * 3_600_000),
      },
    });
    await sweepAlerts(ctx.app.prisma, ctx.app.realtime);
    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(0);

    // A fresh point arms the rule.
    await postPoints(memberDevice.deviceToken, [
      { lat: 31.6, lng: 120.7, accuracy: 10, recordedAt: isoMinutesAgo(1) },
    ]);
    const rule = await ctx.prisma.alertRule.findFirstOrThrow({
      where: { type: 'STALE', follow: { targetId: member.user.id } },
    });
    expect(JSON.parse(rule.state).armed).toBe(true);

    // Age that point past the threshold and sweep.
    await ctx.prisma.location.updateMany({
      where: { userId: member.user.id },
      data: { recordedAt: new Date(Date.now() - 13 * 3_600_000) },
    });
    await sweepAlerts(ctx.app.prisma, ctx.app.realtime);
    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(1);

    // A second sweep must not duplicate the alert.
    await sweepAlerts(ctx.app.prisma, ctx.app.realtime);
    expect(await ctx.prisma.alert.count({ where: { userId: admin.user.id } })).toBe(1);
  });
});

describe('lifecycle', () => {
  it('removes follows when the target leaves the family', async () => {
    const { admin, member } = await setupPair();
    await createFollow(admin.token, member.user.id, [{ type: 'MOVEMENT' }]);

    const remove = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/families/members/${member.user.id}`,
      headers: bearer(admin.token),
    });
    expect(remove.statusCode).toBe(204);

    const list = await ctx.app.inject({
      method: 'GET',
      url: '/api/follows',
      headers: bearer(admin.token),
    });
    expect(list.json<{ follows: unknown[] }>().follows).toHaveLength(0);
    expect(
      await ctx.prisma.alertRule.count({
        where: { follow: { targetId: member.user.id } },
      }),
    ).toBe(0);
  });

  it('keeps alerts private to the recipient', async () => {
    const { admin, member, memberDevice } = await setupPair();
    await createFollow(admin.token, member.user.id, [
      { type: 'LOW_BATTERY', config: { percent: 20 } },
    ]);
    await postPoints(memberDevice.deviceToken, [
      { lat: 31.6, lng: 120.7, accuracy: 10, battery: 9, recordedAt: isoMinutesAgo(1) },
    ]);

    const asTarget = await ctx.app.inject({
      method: 'GET',
      url: '/api/alerts',
      headers: bearer(member.token),
    });
    expect(asTarget.json<{ alerts: unknown[]; unreadCount: number }>()).toEqual({
      alerts: [],
      unreadCount: 0,
    });
  });
});

describe('pure helpers', () => {
  it('computes haversine distance', () => {
    const meters = haversineMeters(31.6, 120.7, 31.603, 120.7);
    expect(meters).toBeGreaterThan(320);
    expect(meters).toBeLessThan(345);
  });

  it('seeds the movement anchor without firing on the first point', () => {
    const now = new Date('2026-11-27T10:00:00.000Z');
    const first = evaluateMovementPoint(
      { thresholdMeters: 200, cooldownMinutes: 30 },
      {},
      'r1',
      '妈妈',
      { lat: 31.6, lng: 120.7, accuracy: 10, recordedAt: now },
      now,
    );
    expect(first.draft).toBeNull();
    expect(first.state.anchorLat).toBe(31.6);
  });
});
