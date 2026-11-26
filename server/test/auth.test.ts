import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  bearer,
  createFamily,
  createTestContext,
  loginUser,
  registerUser,
  TEST_PASSWORD,
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

describe('auth', () => {
  it('registers a user and returns a working JWT', async () => {
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { username: 'alice01', nickname: 'Alice', password: TEST_PASSWORD },
    });

    expect(response.statusCode).toBe(201);
    const body = response.json<{ token: string; user: Record<string, unknown>; familyId: unknown }>();
    expect(body.token).toEqual(expect.any(String));
    expect(body.user).toMatchObject({ username: 'alice01', nickname: 'Alice' });
    expect(body.familyId).toBeNull();

    // HS256, 7 day expiry.
    const [encodedHeader, encodedPayload] = body.token.split('.');
    const header = JSON.parse(Buffer.from(encodedHeader!, 'base64url').toString('utf8')) as {
      alg: string;
    };
    const payload = JSON.parse(Buffer.from(encodedPayload!, 'base64url').toString('utf8')) as {
      sub: string;
      iat: number;
      exp: number;
    };
    expect(header.alg).toBe('HS256');
    expect(payload.sub).toBe(body.user.id);
    expect(payload.exp - payload.iat).toBe(7 * 24 * 60 * 60);

    const me = await ctx.app.inject({
      method: 'GET',
      url: '/api/me',
      headers: bearer(body.token),
    });
    expect(me.statusCode).toBe(200);
    expect(me.json()).toMatchObject({
      user: { username: 'alice01', nickname: 'Alice' },
      family: null,
      devices: [],
    });
  });

  it('rejects duplicate usernames with CONFLICT', async () => {
    await registerUser(ctx.app, 'bob01');
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { username: 'bob01', nickname: 'Bob 2', password: TEST_PASSWORD },
    });

    expect(response.statusCode).toBe(409);
    expect(response.json()).toMatchObject({ error: { code: 'CONFLICT' } });
  });

  it('validates request bodies and uses the contract error shape', async () => {
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: { username: 'carol01', nickname: 'Carol', password: 'short' },
    });

    expect(response.statusCode).toBe(400);
    const body = response.json<{ error: { code: string; message: string } }>();
    expect(body.error.code).toBe('VALIDATION');
    expect(body.error.message).toContain('password');
  });

  it('logs in with valid credentials and rejects invalid ones', async () => {
    await registerUser(ctx.app, 'dave01');

    const success = await ctx.app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username: 'dave01', password: TEST_PASSWORD },
    });
    expect(success.statusCode).toBe(200);
    expect(success.json()).toMatchObject({ user: { username: 'dave01' }, familyId: null });

    const wrongPassword = await ctx.app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username: 'dave01', password: 'wrong-password' },
    });
    expect(wrongPassword.statusCode).toBe(401);
    expect(wrongPassword.json()).toMatchObject({ error: { code: 'UNAUTHORIZED' } });

    const missing = await ctx.app.inject({ method: 'GET', url: '/api/me' });
    expect(missing.statusCode).toBe(401);
    expect(missing.json()).toMatchObject({ error: { code: 'UNAUTHORIZED' } });
  });

  it('joins a family during registration when given a valid invite code', async () => {
    const admin = await registerUser(ctx.app, 'erin01');
    const family = await createFamily(ctx.app, admin.token, '注册家庭');

    const invitee = await registerUser(ctx.app, 'frank01', { inviteCode: family.inviteCode });

    expect(invitee.familyId).toBe(family.id);
    const me = await ctx.app.inject({
      method: 'GET',
      url: '/api/me',
      headers: bearer(invitee.token),
    });
    expect(me.statusCode).toBe(200);
    expect(me.json()).toMatchObject({ family: { id: family.id, name: '注册家庭', role: 'member' } });

    // A registration with an invalid invite code must not create an account.
    const invalidCode = await unusedInviteCode(ctx.prisma);
    const rejected = await ctx.app.inject({
      method: 'POST',
      url: '/api/auth/register',
      payload: {
        username: 'grace01',
        nickname: 'Grace',
        password: TEST_PASSWORD,
        inviteCode: invalidCode,
      },
    });
    expect(rejected.statusCode).toBe(400);
    expect(rejected.json()).toMatchObject({ error: { code: 'INVITE_INVALID' } });

    const loginAttempt = await ctx.app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username: 'grace01', password: TEST_PASSWORD },
    });
    expect(loginAttempt.statusCode).toBe(401);
  });

  it('supports login after registration', async () => {
    await registerUser(ctx.app, 'henry01');
    const session = await loginUser(ctx.app, 'henry01');
    expect(session.user.username).toBe('henry01');
  });

  it('rate limits repeated auth attempts with the contract error shape', async () => {
    let response: Awaited<ReturnType<typeof ctx.app.inject>> | undefined;
    const wrongPassword = ['wrong', 'password', 'for', 'rate', 'limit'].join('-');

    for (let attempt = 0; attempt < 30; attempt += 1) {
      response = await ctx.app.inject({
        method: 'POST',
        url: '/api/auth/login',
        payload: { username: 'nobody-rate', password: wrongPassword },
      });
      if (response.statusCode === 429) break;
    }

    expect(response?.statusCode).toBe(429);
    expect(response?.json()).toMatchObject({ error: { code: 'RATE_LIMITED' } });
  });
});
