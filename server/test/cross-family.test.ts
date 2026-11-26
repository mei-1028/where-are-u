import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  bearer,
  createFamily,
  createFakeSocket,
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

describe('cross-family authorization', () => {
  it('returns 404 for every resource belonging to another family', async () => {
    const adminA = await registerUser(ctx.app, 'crossadmina');
    const familyA = await createFamily(ctx.app, adminA.token, 'A 家');
    const memberA = await registerUser(ctx.app, 'crossmembera');
    await joinFamily(ctx.app, memberA.token, familyA.inviteCode!);
    const deviceA = await registerDevice(ctx.app, adminA.token, 'A 家设备');

    const adminB = await registerUser(ctx.app, 'crossadminb');
    await createFamily(ctx.app, adminB.token, 'B 家');

    const history = await ctx.app.inject({
      method: 'GET',
      url: `/api/devices/${deviceA.device.id}/locations`,
      headers: bearer(adminB.token),
    });
    expect(history.statusCode).toBe(404);
    expect(history.json()).toMatchObject({ error: { code: 'NOT_FOUND' } });

    const patch = await ctx.app.inject({
      method: 'PATCH',
      url: `/api/devices/${deviceA.device.id}`,
      headers: bearer(adminB.token),
      payload: { name: 'hijacked' },
    });
    expect(patch.statusCode).toBe(404);

    const remove = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/devices/${deviceA.device.id}`,
      headers: bearer(adminB.token),
    });
    expect(remove.statusCode).toBe(404);

    const locate = await ctx.app.inject({
      method: 'POST',
      url: `/api/devices/${deviceA.device.id}/locate`,
      headers: bearer(adminB.token),
    });
    expect(locate.statusCode).toBe(404);

    // Create a real command for family A, then try to read it from family B.
    const fakeSocket = createFakeSocket();
    ctx.app.realtime.attach(deviceA.device.id, fakeSocket);
    const locateA = await ctx.app.inject({
      method: 'POST',
      url: `/api/devices/${deviceA.device.id}/locate`,
      headers: bearer(adminA.token),
    });
    expect(locateA.statusCode).toBe(202);
    const { commandId } = locateA.json<{ commandId: string }>();

    const command = await ctx.app.inject({
      method: 'GET',
      url: `/api/commands/${commandId}`,
      headers: bearer(adminB.token),
    });
    expect(command.statusCode).toBe(404);
    expect(command.json()).toMatchObject({ error: { code: 'NOT_FOUND' } });

    // Same-family members may read the device history.
    const sameFamily = await ctx.app.inject({
      method: 'GET',
      url: `/api/devices/${deviceA.device.id}/locations`,
      headers: bearer(memberA.token),
    });
    expect(sameFamily.statusCode).toBe(200);

    // Family B's own lists must not include family A's data.
    const devicesB = await ctx.app.inject({
      method: 'GET',
      url: '/api/devices',
      headers: bearer(adminB.token),
    });
    expect(devicesB.statusCode).toBe(200);
    expect(devicesB.json()).toEqual({ devices: [] });

    const latestB = await ctx.app.inject({
      method: 'GET',
      url: '/api/families/me/latest',
      headers: bearer(adminB.token),
    });
    expect(latestB.statusCode).toBe(200);
    const latestBody = latestB.json<{ members: Array<{ userId: string }> }>();
    expect(latestBody.members).toHaveLength(1);
    expect(latestBody.members[0]!.userId).toBe(adminB.user.id);
  });

  it('restricts other-family members from renaming or deleting a device', async () => {
    const admin = await registerUser(ctx.app, 'permadmin');
    const family = await createFamily(ctx.app, admin.token, '权限家庭');
    const member = await registerUser(ctx.app, 'permmember');
    await joinFamily(ctx.app, member.token, family.inviteCode!);

    const adminDevice = await registerDevice(ctx.app, admin.token, '管理员的设备');
    const memberDevice = await registerDevice(ctx.app, member.token, '成员的设备');

    // Plain members cannot manage someone else's device.
    const memberRename = await ctx.app.inject({
      method: 'PATCH',
      url: `/api/devices/${adminDevice.device.id}`,
      headers: bearer(member.token),
      payload: { name: '改名' },
    });
    expect(memberRename.statusCode).toBe(403);
    expect(memberRename.json()).toMatchObject({ error: { code: 'FORBIDDEN' } });

    const memberDelete = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/devices/${adminDevice.device.id}`,
      headers: bearer(member.token),
    });
    expect(memberDelete.statusCode).toBe(403);

    // Admins may manage member devices.
    const adminRename = await ctx.app.inject({
      method: 'PATCH',
      url: `/api/devices/${memberDevice.device.id}`,
      headers: bearer(admin.token),
      payload: { name: '管理员改的名字' },
    });
    expect(adminRename.statusCode).toBe(200);
    expect(adminRename.json()).toMatchObject({ device: { name: '管理员改的名字' } });

    const adminDelete = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/devices/${memberDevice.device.id}`,
      headers: bearer(admin.token),
    });
    expect(adminDelete.statusCode).toBe(204);

    const revoked = await ctx.app.inject({
      method: 'POST',
      url: '/api/locations',
      headers: bearer(memberDevice.deviceToken),
      payload: {
        points: [{ lat: 30.2741, lng: 120.1551, accuracy: 12, recordedAt: new Date().toISOString() }],
      },
    });
    expect(revoked.statusCode).toBe(401);
  });
});
