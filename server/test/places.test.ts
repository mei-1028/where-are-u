import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  bearer,
  createTestContext,
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

async function seedUser(username: string) {
  const user = await ctx.prisma.user.create({
    data: { username, nickname: username, passwordHash: 'not-used-in-tests' },
  });
  return { user, token: ctx.app.jwt.sign({ sub: user.id }) };
}

async function seedFamily(adminUserId: string, name: string) {
  sequence += 1;
  const family = await ctx.prisma.family.create({
    data: {
      name,
      inviteCode: `${200000 + sequence}`,
      inviteExpiresAt: new Date(Date.now() + 86_400_000),
    },
  });
  await ctx.prisma.familyMember.create({
    data: { familyId: family.id, userId: adminUserId, role: 'admin' },
  });
  return family;
}

async function setupFamily() {
  sequence += 1;
  const suffix = `${sequence}`;
  const admin = await seedUser(`placeadmin${suffix}`);
  const member = await seedUser(`placemember${suffix}`);
  const member2 = await seedUser(`placemember2${suffix}`);
  const family = await seedFamily(admin.user.id, `地点家庭${suffix}`);
  await ctx.prisma.familyMember.createMany({
    data: [
      { familyId: family.id, userId: member.user.id, role: 'member' },
      { familyId: family.id, userId: member2.user.id, role: 'member' },
    ],
  });
  return { admin, member, member2, family };
}

async function createPlace(
  token: string,
  overrides: Record<string, unknown> = {},
) {
  return ctx.app.inject({
    method: 'POST',
    url: '/api/places',
    headers: bearer(token),
    payload: {
      name: `地点${Math.random().toString(36).slice(2, 7)}`,
      type: 'school',
      lat: 31.6,
      lng: 120.7,
      radiusMeters: 200,
      ...overrides,
    },
  });
}

describe('places', () => {
  it('creates, lists and returns the rule usage count', async () => {
    const { admin, member } = await setupFamily();
    const created = await createPlace(admin.token, { name: '学校' });
    expect(created.statusCode).toBe(201);
    const place = created.json<{ place: { id: string; name: string } }>().place;
    expect(place.name).toBe('学校');

    const follow = await ctx.app.inject({
      method: 'POST',
      url: '/api/follows',
      headers: bearer(admin.token),
      payload: {
        targetUserId: member.user.id,
        rules: [
          {
            type: 'GEOFENCE',
            config: { placeId: place.id, event: 'BOTH' },
          },
        ],
      },
    });
    expect(follow.statusCode).toBe(201);

    const list = await ctx.app.inject({
      method: 'GET',
      url: '/api/places',
      headers: bearer(admin.token),
    });
    expect(list.statusCode).toBe(200);
    const places = list.json<{
      places: Array<{ id: string; usedByRules: number }>;
    }>().places;
    expect(places).toHaveLength(1);
    expect(places[0]!.usedByRules).toBe(1);
  });

  it('rejects duplicate names case-insensitively', async () => {
    const { admin } = await setupFamily();
    expect((await createPlace(admin.token, { name: 'Home' })).statusCode).toBe(201);
    const duplicate = await createPlace(admin.token, { name: 'home' });
    expect(duplicate.statusCode).toBe(409);
  });

  it('caps a family at 20 places', async () => {
    const { admin } = await setupFamily();
    for (let index = 0; index < 20; index += 1) {
      const response = await createPlace(admin.token, { name: `地点${index}` });
      expect(response.statusCode).toBe(201);
    }
    const overLimit = await createPlace(admin.token, { name: '第21个' });
    expect(overLimit.statusCode).toBe(400);
  });

  it('lets any member create, but only the creator or an admin edit/delete', async () => {
    const { admin, member, member2 } = await setupFamily();
    const created = await createPlace(member.token, {
      name: '孩子的学校',
      lat: 31.61,
    });
    const place = created.json<{ place: { id: string } }>().place;

    const denied = await ctx.app.inject({
      method: 'PATCH',
      url: `/api/places/${place.id}`,
      headers: bearer(member2.token),
      payload: { radiusMeters: 500 },
    });
    expect(denied.statusCode).toBe(403);

    const byCreator = await ctx.app.inject({
      method: 'PATCH',
      url: `/api/places/${place.id}`,
      headers: bearer(member.token),
      payload: { radiusMeters: 500 },
    });
    expect(byCreator.statusCode).toBe(200);
    expect(byCreator.json<{ place: { radiusMeters: number } }>().place.radiusMeters).toBe(500);

    const byAdmin = await ctx.app.inject({
      method: 'PATCH',
      url: `/api/places/${place.id}`,
      headers: bearer(admin.token),
      payload: { name: '学校' },
    });
    expect(byAdmin.statusCode).toBe(200);

    const deniedDelete = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/places/${place.id}`,
      headers: bearer(member2.token),
    });
    expect(deniedDelete.statusCode).toBe(403);

    const deleted = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/places/${place.id}`,
      headers: bearer(member.token),
    });
    expect(deleted.statusCode).toBe(200);
  });

  it('deletes referencing geofence rules and reports the count', async () => {
    const { admin, member } = await setupFamily();
    const created = await createPlace(admin.token, { name: '公司' });
    const place = created.json<{ place: { id: string } }>().place;

    await ctx.app.inject({
      method: 'POST',
      url: '/api/follows',
      headers: bearer(admin.token),
      payload: {
        targetUserId: member.user.id,
        rules: [
          { type: 'GEOFENCE', config: { placeId: place.id, event: 'ENTER' } },
        ],
      },
    });

    const removed = await ctx.app.inject({
      method: 'DELETE',
      url: `/api/places/${place.id}`,
      headers: bearer(admin.token),
    });
    expect(removed.statusCode).toBe(200);
    expect(removed.json<{ removedRules: number }>().removedRules).toBe(1);

    const follows = await ctx.app.inject({
      method: 'GET',
      url: '/api/follows',
      headers: bearer(admin.token),
    });
    const rules = follows.json<{ follows: Array<{ rules: Array<{ type: string }> }> }>()
      .follows.flatMap((follow) => follow.rules);
    expect(rules.some((rule) => rule.type === 'GEOFENCE')).toBe(false);
  });

  it('hides places from other families and rejects their ids', async () => {
    const { admin, member } = await setupFamily();
    const outsider = await seedUser(`placeoutsider${sequence}`);
    const outsiderFamily = await seedFamily(outsider.user.id, `外部家庭${sequence}`);
    const outsiderPlace = await ctx.prisma.place.create({
      data: {
        familyId: outsiderFamily.id,
        createdBy: outsider.user.id,
        name: '外部的家',
        nameKey: '外部的家',
        lat: 31.5,
        lng: 120.6,
      },
    });

    const list = await ctx.app.inject({
      method: 'GET',
      url: '/api/places',
      headers: bearer(admin.token),
    });
    expect(list.json<{ places: unknown[] }>().places).toHaveLength(0);

    const crossRule = await ctx.app.inject({
      method: 'POST',
      url: '/api/follows',
      headers: bearer(admin.token),
      payload: {
        targetUserId: member.user.id,
        rules: [
          { type: 'GEOFENCE', config: { placeId: outsiderPlace.id } },
        ],
      },
    });
    expect(crossRule.statusCode).toBe(404);

    const crossPatch = await ctx.app.inject({
      method: 'PATCH',
      url: `/api/places/${outsiderPlace.id}`,
      headers: bearer(admin.token),
      payload: { radiusMeters: 300 },
    });
    expect(crossPatch.statusCode).toBe(404);
  });

  it('rejects a second geofence rule for the same place', async () => {
    const { admin, member } = await setupFamily();
    const created = await createPlace(admin.token, { name: '重复地点' });
    const place = created.json<{ place: { id: string } }>().place;

    const first = await ctx.app.inject({
      method: 'POST',
      url: '/api/follows',
      headers: bearer(admin.token),
      payload: {
        targetUserId: member.user.id,
        rules: [{ type: 'GEOFENCE', config: { placeId: place.id } }],
      },
    });
    expect(first.statusCode).toBe(201);
    const followId = first.json<{ follow: { id: string } }>().follow.id;

    const duplicate = await ctx.app.inject({
      method: 'POST',
      url: `/api/follows/${followId}/rules`,
      headers: bearer(admin.token),
      payload: { type: 'GEOFENCE', config: { placeId: place.id } },
    });
    expect(duplicate.statusCode).toBe(409);
  });
});
