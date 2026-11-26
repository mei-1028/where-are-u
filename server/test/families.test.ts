import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  bearer,
  createFamily,
  createTestContext,
  joinFamily,
  registerDevice,
  registerUser,
  unusedInviteCode,
  type TestContext,
} from './helpers.ts';

let ctx: TestContext;

beforeAll(async () => {
  ctx = await createTestContext();
});

afterAll(async () => {
  if (ctx) await ctx.cleanup();
});

function validPoint(recordedAt = new Date().toISOString()) {
  return {
    lat: 30.2741,
    lng: 120.1551,
    accuracy: 12,
    battery: 80,
    provider: 'amap',
    recordedAt,
  };
}

describe('families', () => {
  it('creates a family with a 6-digit invite code and exposes it only to admins', async () => {
    const admin = await registerUser(ctx.app, 'famadmin1');
    const family = await createFamily(ctx.app, admin.token, '陈家一号');

    expect(family).toMatchObject({ name: '陈家一号', role: 'admin' });
    expect(family.inviteCode).toMatch(/^\d{6}$/);
    expect(Number.isNaN(Date.parse(family.inviteExpiresAt!))).toBe(false);

    const overview = await ctx.app.inject({
      method: 'GET',
      url: '/api/families/me',
      headers: bearer(admin.token),
    });
    expect(overview.statusCode).toBe(200);
    const body = overview.json<{
      family: { id: string; name: string; role: string; inviteCode?: string; inviteExpiresAt?: string };
      members: Array<Record<string, unknown>>;
    }>();
    expect(body.family).toMatchObject({ id: family.id, name: '陈家一号', role: 'admin' });
    expect(body.family.inviteCode).toMatch(/^\d{6}$/);
    expect(body.family.inviteExpiresAt).toBe(family.inviteExpiresAt);
    expect(body.members).toHaveLength(1);
    expect(body.members[0]).toMatchObject({
      nickname: 'famadmin1',
      role: 'admin',
      sharingPaused: false,
      online: false,
      lastLocation: null,
    });

    const member = await registerUser(ctx.app, 'fammember1');
    await joinFamily(ctx.app, member.token, family.inviteCode!);

    const memberView = await ctx.app.inject({
      method: 'GET',
      url: '/api/families/me',
      headers: bearer(member.token),
    });
    expect(memberView.statusCode).toBe(200);
    const memberBody = memberView.json<{ family: Record<string, unknown>; members: unknown[] }>();
    expect(memberBody.family).toMatchObject({ id: family.id, role: 'member' });
    expect(memberBody.family).not.toHaveProperty('inviteCode');
    expect(memberBody.family).not.toHaveProperty('inviteExpiresAt');
    expect(memberBody.members).toHaveLength(2);

    const createAgain = await ctx.app.inject({
      method: 'POST',
      url: '/api/families',
      headers: bearer(member.token),
      payload: { name: '另一个家' },
    });
    expect(createAgain.statusCode).toBe(409);
    expect(createAgain.json()).toMatchObject({ error: { code: 'ALREADY_IN_FAMILY' } });
  });

  it('joins by code and rejects duplicate joins and invalid codes', async () => {
    const admin = await registerUser(ctx.app, 'famadmin2');
    const family = await createFamily(ctx.app, admin.token);

    const member = await registerUser(ctx.app, 'fammember2');
    const joined = await joinFamily(ctx.app, member.token, family.inviteCode!);
    expect(joined).toMatchObject({ id: family.id, role: 'member' });

    const duplicate = await ctx.app.inject({
      method: 'POST',
      url: '/api/families/join',
      headers: bearer(member.token),
      payload: { code: family.inviteCode },
    });
    expect(duplicate.statusCode).toBe(409);
    expect(duplicate.json()).toMatchObject({ error: { code: 'ALREADY_IN_FAMILY' } });

    const outsider = await registerUser(ctx.app, 'famoutsider2');
    const invalid = await ctx.app.inject({
      method: 'POST',
      url: '/api/families/join',
      headers: bearer(outsider.token),
      payload: { code: await unusedInviteCode(ctx.prisma) },
    });
    expect(invalid.statusCode).toBe(400);
    expect(invalid.json()).toMatchObject({ error: { code: 'INVITE_INVALID' } });
  });

  it('rejects an expired invite code', async () => {
    const admin = await registerUser(ctx.app, 'famadmin3');
    const family = await createFamily(ctx.app, admin.token);
    const joiner = await registerUser(ctx.app, 'famjoiner3');

    await ctx.prisma.family.update({
      where: { id: family.id },
      data: { inviteExpiresAt: new Date(Date.now() - 1000) },
    });

    const response = await ctx.app.inject({
      method: 'POST',
      url: '/api/families/join',
      headers: bearer(joiner.token),
      payload: { code: family.inviteCode },
    });
    expect(response.statusCode).toBe(400);
    expect(response.json()).toMatchObject({ error: { code: 'INVITE_EXPIRED' } });
  });

  it('resets the invite code (admin only) and invalidates the old one', async () => {
    const admin = await registerUser(ctx.app, 'famadmin4');
    const family = await createFamily(ctx.app, admin.token);
    const member = await registerUser(ctx.app, 'fammember4');
    await joinFamily(ctx.app, member.token, family.inviteCode!);

    const denied = await ctx.app.inject({
      method: 'POST',
      url: '/api/families/invite/reset',
      headers: bearer(member.token),
    });
    expect(denied.statusCode).toBe(403);
    expect(denied.json()).toMatchObject({ error: { code: 'FORBIDDEN' } });

    const reset = await ctx.app.inject({
      method: 'POST',
      url: '/api/families/invite/reset',
      headers: bearer(admin.token),
    });
    expect(reset.statusCode).toBe(200);
    const body = reset.json<{ inviteCode: string; expiresAt: string }>();
    expect(body.inviteCode).toMatch(/^\d{6}$/);
    expect(body.inviteCode).not.toBe(family.inviteCode);
    expect(new Date(body.expiresAt).getTime()).toBeGreaterThan(Date.now());

    const oldCodeUser = await registerUser(ctx.app, 'famold4');
    const oldCode = await ctx.app.inject({
      method: 'POST',
      url: '/api/families/join',
      headers: bearer(oldCodeUser.token),
      payload: { code: family.inviteCode },
    });
    expect(oldCode.statusCode).toBe(400);
    expect(oldCode.json()).toMatchObject({ error: { code: 'INVITE_INVALID' } });

    const newCodeUser = await registerUser(ctx.app, 'famnew4');
    const newCode = await ctx.app.inject({
      method: 'POST',
      url: '/api/families/join',
      headers: bearer(newCodeUser.token),
      payload: { code: body.inviteCode },
    });
    expect(newCode.statusCode).toBe(200);
  });

  it('removes a member (admin only), revokes devices but keeps their history', async () => {
    const admin = await registerUser(ctx.app, 'famadmin5');
    const family = await createFamily(ctx.app, admin.token);
    const member = await registerUser(ctx.app, 'fammember5');
    await joinFamily(ctx.app, member.token, family.inviteCode!);
    const device = await registerDevice(ctx.app, member.token, '被移除成员的设备');

    const historyStart = new Date(Date.now() - 60 * 60 * 1000);
    const historyEnd = new Date(Date.now() + 60 * 60 * 1000);
    const ingestBeforeRemoval = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: { points: [validPoint(), validPoint(new Date(Date.now() - 1000).toISOString())] },
    });
    expect(ingestBeforeRemoval.statusCode).toBe(202);
    expect(ingestBeforeRemoval.json()).toEqual({ accepted: 2 });

    const selfRemoval = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/families/members/${admin.user.id}`,
      headers: bearer(admin.token),
    });
    expect(selfRemoval.statusCode).toBe(400);
    expect(selfRemoval.json()).toMatchObject({ error: { code: 'VALIDATION' } });

    const denied = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/families/members/${admin.user.id}`,
      headers: bearer(member.token),
    });
    expect(denied.statusCode).toBe(403);
    expect(denied.json()).toMatchObject({ error: { code: 'FORBIDDEN' } });

    const removed = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/families/members/${member.user.id}`,
      headers: bearer(admin.token),
    });
    expect(removed.statusCode).toBe(204);

    const memberOverview = await ctx.app.inject({
      method: 'GET',
      url: '/api/families/me',
      headers: bearer(member.token),
    });
    expect(memberOverview.statusCode).toBe(404);
    expect(memberOverview.json()).toMatchObject({ error: { code: 'NOT_FOUND' } });

    const deviceRow = await ctx.prisma.device.findUnique({ where: { id: device.device.id } });
    expect(deviceRow).not.toBeNull();
    expect(deviceRow?.active).toBe(false);
    expect(deviceRow?.revokedAt).toBeInstanceOf(Date);

    const ingestWithRevokedToken = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: { points: [validPoint()] },
    });
    expect(ingestWithRevokedToken.statusCode).toBe(401);
    expect(ingestWithRevokedToken.json()).toMatchObject({ error: { code: 'UNAUTHORIZED' } });

    // History is retained and still readable by the remaining family admin.
    expect(await ctx.prisma.location.count({ where: { deviceId: device.device.id } })).toBe(2);
    const history = await ctx.app.inject({
      method: 'GET',
      url: `/api/devices/${device.device.id}/locations?from=${encodeURIComponent(historyStart.toISOString())}&to=${encodeURIComponent(historyEnd.toISOString())}`,
      headers: bearer(admin.token),
    });
    expect(history.statusCode).toBe(200);
    expect(history.json<{ points: unknown[] }>().points).toHaveLength(2);

    // The removed member has no family membership, so the same history is 404.
    const removedHistory = await ctx.app.inject({
      method: 'GET',
      url: `/api/devices/${device.device.id}/locations`,
      headers: bearer(member.token),
    });
    expect(removedHistory.statusCode).toBe(404);

    const devices = await ctx.app.inject({
      method: 'GET',
      url: '/api/devices',
      headers: bearer(admin.token),
    });
    expect(devices.json()).toEqual({ devices: [] });

    const removedMe = await ctx.app.inject({
      method: 'GET',
      url: '/api/me',
      headers: bearer(member.token),
    });
    expect(removedMe.json()).toMatchObject({ family: null, devices: [] });
  });

  it('pauses and resumes sharing for the current member', async () => {
    const admin = await registerUser(ctx.app, 'famadmin7');
    const family = await createFamily(ctx.app, admin.token);
    const member = await registerUser(ctx.app, 'fammember7');
    await joinFamily(ctx.app, member.token, family.inviteCode!);
    const device = await registerDevice(ctx.app, member.token, '成员设备');

    const paused = await ctx.app.inject({
      method: 'PATCH',
      url: '/api/families/me/sharing',
      headers: bearer(member.token),
      payload: { paused: true },
    });
    expect(paused.statusCode).toBe(200);
    expect(paused.json()).toEqual({ sharingPaused: true });

    const pausedOverview = await ctx.app.inject({
      method: 'GET',
      url: '/api/families/me',
      headers: bearer(admin.token),
    });
    expect(pausedOverview.statusCode).toBe(200);
    const pausedMembers = pausedOverview.json<{
      members: Array<{ userId: string; sharingPaused: boolean }>;
    }>().members;
    expect(pausedMembers.find((entry) => entry.userId === member.user.id)?.sharingPaused).toBe(true);
    expect(pausedMembers.find((entry) => entry.userId === admin.user.id)?.sharingPaused).toBe(false);

    // Pausing sharing only stops the client from reporting new points; the
    // server still accepts historical backfill (contract §3).
    const backfill = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: { points: [validPoint()] },
    });
    expect(backfill.statusCode).toBe(202);

    const resumed = await ctx.app.inject({
      method: 'PATCH',
      url: '/api/families/me/sharing',
      headers: bearer(member.token),
      payload: { paused: false },
    });
    expect(resumed.statusCode).toBe(200);
    expect(resumed.json()).toEqual({ sharingPaused: false });

    const resumedOverview = await ctx.app.inject({
      method: 'GET',
      url: '/api/families/me',
      headers: bearer(admin.token),
    });
    const resumedMembers = resumedOverview.json<{
      members: Array<{ userId: string; sharingPaused: boolean }>;
    }>().members;
    expect(resumedMembers.find((entry) => entry.userId === member.user.id)?.sharingPaused).toBe(false);

    const invalid = await ctx.app.inject({
      method: 'PATCH',
      url: '/api/families/me/sharing',
      headers: bearer(member.token),
      payload: { paused: 'yes' },
    });
    expect(invalid.statusCode).toBe(400);
    expect(invalid.json()).toMatchObject({ error: { code: 'VALIDATION' } });

    const unauthenticated = await ctx.app.inject({
      method: 'PATCH',
      url: '/api/families/me/sharing',
      payload: { paused: true },
    });
    expect(unauthenticated.statusCode).toBe(401);
  });

  it('lets members leave, revokes their devices and keeps history', async () => {
    const admin = await registerUser(ctx.app, 'famadmin6');
    const family = await createFamily(ctx.app, admin.token);
    const member = await registerUser(ctx.app, 'fammember6');
    await joinFamily(ctx.app, member.token, family.inviteCode!);
    const device = await registerDevice(ctx.app, member.token, '成员设备');

    const historyStart = new Date(Date.now() - 60 * 60 * 1000);
    const historyEnd = new Date(Date.now() + 60 * 60 * 1000);
    await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(device.deviceToken),
      payload: { points: [validPoint()] },
    });

    const adminLeave = await ctx.app.inject({
      method: 'POST',
      url: '/api/families/leave',
      headers: bearer(admin.token),
    });
    expect(adminLeave.statusCode).toBe(403);
    expect(adminLeave.json()).toMatchObject({ error: { code: 'FORBIDDEN' } });

    const memberLeave = await ctx.app.inject({
      method: 'POST',
      url: '/api/families/leave',
      headers: bearer(member.token),
    });
    expect(memberLeave.statusCode).toBe(204);

    const me = await ctx.app.inject({
      method: 'GET',
      url: '/api/me',
      headers: bearer(member.token),
    });
    expect(me.statusCode).toBe(200);
    expect(me.json()).toMatchObject({ family: null, devices: [] });

    const devices = await ctx.prisma.device.findMany({ where: { userId: member.user.id } });
    expect(devices).toHaveLength(1);
    expect(devices[0]?.active).toBe(false);
    expect(devices[0]?.revokedAt).toBeInstanceOf(Date);
    expect(await ctx.prisma.device.count({ where: { userId: member.user.id, active: true } })).toBe(0);

    const history = await ctx.app.inject({
      method: 'GET',
      url: `/api/devices/${device.device.id}/locations?from=${encodeURIComponent(historyStart.toISOString())}&to=${encodeURIComponent(historyEnd.toISOString())}`,
      headers: bearer(admin.token),
    });
    expect(history.statusCode).toBe(200);
    expect(history.json<{ points: unknown[] }>().points).toHaveLength(1);
  });
});
