import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { GeofenceConfig } from '../src/lib/validation.ts';
import {
  evaluateGeofencePoint,
  isWithinGeofenceWindow,
} from '../src/services/alerts.ts';
import {
  bearer,
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

async function seedUser(username: string) {
  const user = await ctx.prisma.user.create({
    data: { username, nickname: username, passwordHash: 'not-used-in-tests' },
  });
  return { user, token: ctx.app.jwt.sign({ sub: user.id }) };
}

async function seedFamily(adminUserId: string, name: string) {
  sequence += 1;
  return ctx.prisma.family.create({
    data: {
      name,
      inviteCode: `${300000 + sequence}`,
      inviteExpiresAt: new Date(Date.now() + 86_400_000),
    },
  });
}

async function setupPair() {
  sequence += 1;
  const suffix = `${sequence}`;
  const follower = await seedUser(`geoadmin${suffix}`);
  const target = await seedUser(`geotarget${suffix}`);
  const family = await seedFamily(follower.user.id, `围栏家庭${suffix}`);
  await ctx.prisma.familyMember.createMany({
    data: [
      { familyId: family.id, userId: follower.user.id, role: 'admin' },
      { familyId: family.id, userId: target.user.id, role: 'member' },
    ],
  });
  const targetDevice = await registerDevice(ctx.app, target.token, '目标手机');
  return { follower, target, family, targetDevice };
}

async function createPlace(token: string, name: string, lat = 31.6, lng = 120.7) {
  const response = await ctx.app.inject({
    method: 'POST',
    url: '/api/places',
    headers: bearer(token),
    payload: { name, type: 'school', lat, lng, radiusMeters: 200 },
  });
  if (response.statusCode !== 201) {
    throw new Error(`createPlace failed: ${response.statusCode} ${response.body}`);
  }
  return response.json<{ place: { id: string } }>().place;
}

async function createGeofenceRule(
  token: string,
  targetUserId: string,
  placeId: string,
  config: Record<string, unknown> = {},
) {
  const response = await ctx.app.inject({
    method: 'POST',
    url: '/api/follows',
    headers: bearer(token),
    payload: {
      targetUserId,
      rules: [
        {
          type: 'GEOFENCE',
          config: { placeId, event: 'BOTH', ...config },
        },
      ],
    },
  });
  if (response.statusCode !== 201) {
    throw new Error(`createFollow failed: ${response.statusCode} ${response.body}`);
  }
  return response.json<{
    follow: { id: string; rules: Array<{ id: string }> };
  }>().follow;
}

function isoIn(seconds: number): string {
  return new Date(Date.now() + seconds * 1000).toISOString();
}

async function postPoints(
  deviceToken: string,
  points: Array<Record<string, unknown>>,
) {
  const response = await ctx.app.inject({
    method: 'POST',
    url: '/api/locations',
    headers: bearer(deviceToken),
    payload: { points },
  });
  expect(response.statusCode).toBe(202);
}

function point(lat: number, lng: number, seconds: number, accuracy = 10) {
  return { lat, lng, accuracy, provider: 'test', recordedAt: isoIn(seconds) };
}

/** A China-time HH:mm string offset from now by the given minutes. */
function chinaClock(offsetMinutes: number): string {
  const shifted = new Date(Date.now() + 8 * 3_600_000 + offsetMinutes * 60_000);
  return `${String(shifted.getUTCHours()).padStart(2, '0')}:${String(
    shifted.getUTCMinutes(),
  ).padStart(2, '0')}`;
}

const place = { id: 'p1', name: '学校', lat: 31.6, lng: 120.7, radiusMeters: 200 };
const config: GeofenceConfig = { placeId: 'p1', event: 'BOTH', window: null };
const baseState = {
  inside: null,
  lastFiredAt: null,
  lastRecordedAtMs: null,
  epochMs: 0,
};

function evalPoint(
  lat: number,
  lng: number,
  minutesFromNow: number,
  accuracy = 10,
  state: unknown = baseState,
  ruleConfig: GeofenceConfig = config,
) {
  return evaluateGeofencePoint(
    ruleConfig,
    place,
    state,
    'r1',
    '小明',
    {
      lat,
      lng,
      accuracy,
      recordedAt: new Date(Date.now() + minutesFromNow * 60_000),
    },
    new Date(),
  );
}

describe('geofence window', () => {
  it('matches same-day windows and weekday filters', () => {
    const window = { days: [1, 2, 3, 4, 5], start: '07:00', end: '09:00' };
    // 2026-12-04 is a Friday; 00:30Z = 08:30 China.
    expect(
      isWithinGeofenceWindow(window, new Date('2026-12-04T00:30:00.000Z')),
    ).toBe(true);
    // 2026-12-05 is a Saturday.
    expect(
      isWithinGeofenceWindow(window, new Date('2026-12-05T00:30:00.000Z')),
    ).toBe(false);
    // End boundary is exclusive.
    expect(
      isWithinGeofenceWindow(window, new Date('2026-12-04T01:00:00.000Z')),
    ).toBe(false);
    expect(isWithinGeofenceWindow(null, new Date())).toBe(true);
  });

  it('handles overnight windows using the start day', () => {
    const window = { days: [1], start: '22:00', end: '06:00' };
    // Monday 2026-11-30 23:00 China = 15:00Z.
    expect(
      isWithinGeofenceWindow(window, new Date('2026-11-30T15:00:00.000Z')),
    ).toBe(true);
    // Tuesday 02:00 China = Monday 18:00Z (window started Monday).
    expect(
      isWithinGeofenceWindow(window, new Date('2026-11-30T18:00:00.000Z')),
    ).toBe(true);
    // Tuesday 07:00 China = Monday 23:00Z: past the end.
    expect(
      isWithinGeofenceWindow(window, new Date('2026-11-30T23:00:00.000Z')),
    ).toBe(false);
    // Monday 21:00 China = 13:00Z: before the start.
    expect(
      isWithinGeofenceWindow(window, new Date('2026-11-30T13:00:00.000Z')),
    ).toBe(false);
  });
});

describe('geofence evaluation', () => {
  it('establishes a baseline without firing, then reports enter and leave', () => {
    const first = evalPoint(31.62, 120.7, 0); // ~2.2 km away
    expect(first.draft).toBeNull();
    expect(first.state.inside).toBe(false);

    const entered = evalPoint(31.6005, 120.7, 1, 10, first.state); // ~55 m
    expect(entered.state.inside).toBe(true);
    expect(entered.draft?.data.event).toBe('ENTER');
    expect(entered.draft?.title).toBe('小明 到学校了');

    const left = evalPoint(31.62, 120.7, 2, 10, entered.state);
    expect(left.state.inside).toBe(false);
    expect(left.draft?.data.event).toBe('LEAVE');
    expect(left.draft?.title).toBe('小明 离开学校了');
  });

  it('does not flip when a fix sits inside the accuracy band', () => {
    const outside = evalPoint(31.62, 120.7, 0);
    // 210m from a 200m fence, accuracy 100m -> ambiguous.
    const ambiguous = evalPoint(31.6019, 120.7, 1, 100, outside.state);
    expect(ambiguous.state.inside).toBe(false);
    expect(ambiguous.draft).toBeNull();
  });

  it('caps the band so a small place can still be entered', () => {
    const small = { ...place, radiusMeters: 80 };
    const outside = evaluateGeofencePoint(
      config,
      small,
      baseState,
      'r1',
      '小明',
      { lat: 31.6015, lng: 120.7, accuracy: 10, recordedAt: new Date(Date.now() + 1000) },
      new Date(),
    );
    expect(outside.state.inside).toBe(false);
    // 44m from the center with 60m accuracy: the capped band is 40m, so this
    // is decisively inside (44 + 40 < ... no: 44 <= 80 - 40 = 40 false).
    const entered = evaluateGeofencePoint(
      config,
      small,
      outside.state,
      'r1',
      '小明',
      { lat: 31.6003, lng: 120.7, accuracy: 60, recordedAt: new Date(Date.now() + 2000) },
      new Date(),
    );
    expect(entered.state.inside).toBe(true);
    expect(entered.draft?.data.event).toBe('ENTER');
  });

  it('ignores points recorded before the rule epoch or the cursor', () => {
    const now = Date.now();
    const state = { ...baseState, epochMs: now + 60_000 };
    const gated = evaluateGeofencePoint(
      config,
      place,
      state,
      'r1',
      '小明',
      { lat: 31.6005, lng: 120.7, accuracy: 10, recordedAt: new Date(now) },
      new Date(),
    );
    expect(gated.state.inside).toBeNull();
    expect(gated.draft).toBeNull();

    const duplicate = evaluateGeofencePoint(
      config,
      place,
      { ...baseState, inside: false, lastRecordedAtMs: now + 120_000 },
      'r1',
      '小明',
      { lat: 31.6005, lng: 120.7, accuracy: 10, recordedAt: new Date(now + 60_000) },
      new Date(),
    );
    expect(duplicate.state.inside).toBe(false);
    expect(duplicate.draft).toBeNull();
  });

  it('updates the state without firing outside the time window', () => {
    const window = { days: [], start: '07:00', end: '09:00' };
    const tenAmChina = new Date('2026-12-04T02:00:00.000Z');
    const outside = {
      ...baseState,
      inside: false,
      lastRecordedAtMs: tenAmChina.getTime() - 60_000,
      epochMs: 0,
    };
    const result = evaluateGeofencePoint(
      { ...config, window },
      place,
      outside,
      'r1',
      '小明',
      { lat: 31.6005, lng: 120.7, accuracy: 10, recordedAt: tenAmChina },
      tenAmChina,
    );
    expect(result.state.inside).toBe(true);
    expect(result.draft).toBeNull();
  });

  it('marks a stale backlog transition silent', () => {
    const old = new Date(Date.now() - 60 * 60_000);
    const result = evaluateGeofencePoint(
      config,
      place,
      { ...baseState, inside: false, lastRecordedAtMs: old.getTime() - 1000, epochMs: 0 },
      'r1',
      '小明',
      { lat: 31.6005, lng: 120.7, accuracy: 10, recordedAt: old },
      new Date(),
    );
    expect(result.draft?.silent).toBe(true);
  });

  it('respects the configured event direction', () => {
    const enterOnly: GeofenceConfig = { ...config, event: 'ENTER' };
    const outside = evalPoint(31.62, 120.7, 0, 10, baseState, enterOnly);
    const entered = evalPoint(31.6005, 120.7, 1, 10, outside.state, enterOnly);
    expect(entered.draft).not.toBeNull();
    const left = evalPoint(31.62, 120.7, 2, 10, entered.state, enterOnly);
    expect(left.draft).toBeNull();
    expect(left.state.inside).toBe(false);
  });
});

describe('geofence ingest', () => {
  it('fires enter and leave alerts for a followed member', async () => {
    const { follower, target, targetDevice } = await setupPair();
    const school = await createPlace(follower.token, '测试学校');
    await createGeofenceRule(follower.token, target.user.id, school.id);

    // Transitions must be more than the 60s event-time gap apart.
    await postPoints(targetDevice.deviceToken, [point(31.62, 120.7, 5)]);
    await postPoints(targetDevice.deviceToken, [point(31.6005, 120.7, 70)]);
    await postPoints(targetDevice.deviceToken, [point(31.62, 120.7, 140)]);

    const inbox = await ctx.app.inject({
      method: 'GET',
      url: '/api/alerts',
      headers: bearer(follower.token),
    });
    const alerts = inbox.json<{
      alerts: Array<{ type: string; title: string; data: Record<string, unknown> }>;
    }>().alerts;
    expect(alerts).toHaveLength(2);
    expect(alerts.map((alert) => alert.type)).toEqual(['GEOFENCE', 'GEOFENCE']);
    const nickname = target.user.username;
    const titles = alerts.map((alert) => alert.title).sort();
    expect(titles).toEqual(
      [
        `${nickname} 到测试学校了`,
        `${nickname} 离开测试学校了`,
      ].sort(),
    );
  });

  it('does not fire when the point is outside the rule window', async () => {
    const { follower, target, targetDevice } = await setupPair();
    const school = await createPlace(follower.token, '时段学校');
    await createGeofenceRule(follower.token, target.user.id, school.id, {
      window: { days: [], start: chinaClock(120), end: chinaClock(121) },
    });

    await postPoints(targetDevice.deviceToken, [point(31.62, 120.7, 5)]);
    await postPoints(targetDevice.deviceToken, [point(31.6005, 120.7, 10)]);

    const inbox = await ctx.app.inject({
      method: 'GET',
      url: '/api/alerts',
      headers: bearer(follower.token),
    });
    expect(inbox.json<{ alerts: unknown[] }>().alerts).toHaveLength(0);
  });

  it('skips evaluation while sharing is paused and re-baselines on resume', async () => {
    const { follower, target, targetDevice } = await setupPair();
    const school = await createPlace(follower.token, '暂停学校');
    await createGeofenceRule(follower.token, target.user.id, school.id);

    const pause = await ctx.app.inject({
      method: 'PATCH',
      url: '/api/families/me/sharing',
      headers: bearer(target.token),
      payload: { paused: true },
    });
    expect(pause.statusCode).toBe(200);

    await postPoints(targetDevice.deviceToken, [point(31.62, 120.7, 5)]);
    await postPoints(targetDevice.deviceToken, [point(31.6005, 120.7, 10)]);
    let inbox = await ctx.app.inject({
      method: 'GET',
      url: '/api/alerts',
      headers: bearer(follower.token),
    });
    expect(inbox.json<{ alerts: unknown[] }>().alerts).toHaveLength(0);

    await ctx.app.inject({
      method: 'PATCH',
      url: '/api/families/me/sharing',
      headers: bearer(target.token),
      payload: { paused: false },
    });

    // First point after resume only establishes a baseline.
    await postPoints(targetDevice.deviceToken, [point(31.62, 120.7, 20)]);
    inbox = await ctx.app.inject({
      method: 'GET',
      url: '/api/alerts',
      headers: bearer(follower.token),
    });
    expect(inbox.json<{ alerts: unknown[] }>().alerts).toHaveLength(0);
  });

  it('resets the baseline when a geofence rule is re-enabled', async () => {
    const { follower, target } = await setupPair();
    const school = await createPlace(follower.token, '重置学校');
    const follow = await createGeofenceRule(follower.token, target.user.id, school.id);
    const ruleId = follow.rules[0]!.id;

    const disabled = await ctx.app.inject({
      method: 'PATCH',
      url: `/api/follows/${follow.id}/rules/${ruleId}`,
      headers: bearer(follower.token),
      payload: { enabled: false },
    });
    expect(disabled.statusCode).toBe(200);

    const enabled = await ctx.app.inject({
      method: 'PATCH',
      url: `/api/follows/${follow.id}/rules/${ruleId}`,
      headers: bearer(follower.token),
      payload: { enabled: true },
    });
    expect(enabled.statusCode).toBe(200);

    const stored = await ctx.prisma.alertRule.findUniqueOrThrow({
      where: { id: ruleId },
    });
    expect(JSON.parse(stored.state).inside).toBeNull();
    expect(typeof JSON.parse(stored.state).epochMs).toBe('number');
  });
});
