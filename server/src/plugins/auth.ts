import jwt from '@fastify/jwt';
import type { FastifyRequest } from 'fastify';
import fp from 'fastify-plugin';
import { sha256Hex } from '../lib/crypto.ts';
import { unauthorized } from '../lib/errors.ts';

function extractBearerToken(header: string | undefined): string | null {
  if (!header) return null;
  const [scheme, ...rest] = header.trim().split(/\s+/);
  if (!scheme || scheme.toLowerCase() !== 'bearer') return null;
  const token = rest.join('');
  return token.length > 0 ? token : null;
}

/**
 * User JWT authentication (HS256, 7 days) and device-token authentication.
 * The two schemes are deliberately kept separate: device tokens are only
 * accepted on routes that call `requireDevice`.
 */
export const authPlugin = fp(
  async (app) => {
    await app.register(jwt, {
      secret: app.config.jwtSecret,
      sign: { expiresIn: '7d', algorithm: 'HS256' },
      verify: { algorithms: ['HS256'] },
    });

    app.decorateRequest('authUser', null);
    app.decorateRequest('authDevice', null);

    app.decorate('requireUser', async (request: FastifyRequest) => {
      request.authUser = null;
      request.authDevice = null;

      const token = extractBearerToken(request.headers.authorization);
      if (!token) throw unauthorized();

      try {
        const payload = app.jwt.verify<{ sub?: string }>(token, { algorithms: ['HS256'] });
        if (!payload.sub) throw new Error('JWT is missing a subject');
        request.authUser = { userId: payload.sub };
      } catch {
        throw unauthorized('Invalid or expired token');
      }
    });

    app.decorate('requireDevice', async (request: FastifyRequest) => {
      request.authUser = null;
      request.authDevice = null;

      const token = extractBearerToken(request.headers.authorization);
      if (!token) throw unauthorized('Device token required');

      // Only the SHA-256 hash is stored, so the token itself never reaches the DB lookup.
      const tokenHash = sha256Hex(token);
      const device = await app.prisma.device.findUnique({
        where: { tokenHash },
        select: { id: true, userId: true, familyId: true, active: true },
      });
      // Revoked devices keep their token hash for auditability, but the token
      // must behave exactly like an unknown token.
      if (!device || !device.active) throw unauthorized('Invalid device token');

      request.authDevice = {
        deviceId: device.id,
        userId: device.userId,
        familyId: device.familyId,
      };
    });
  },
  { name: 'auth' },
);
