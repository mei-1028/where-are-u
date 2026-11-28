import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { parseInput, userIdParam } from '../lib/validation.ts';
import {
  createFamily,
  getFamilyOverview,
  joinFamily,
  leaveFamily,
  removeMember,
  resetInvite,
  setSharingPaused,
} from '../services/families.ts';

const createFamilySchema = z.object({
  name: z.string().trim().min(1).max(50),
});

const joinFamilySchema = z.object({
  code: z
    .string()
    .trim()
    .regex(/^\d{6}$/, 'Invite code must be 6 digits'),
});

const sharingSchema = z.object({
  paused: z.boolean(),
});

export const familyRoutes: FastifyPluginAsync = async (app) => {
  app.addHook('preHandler', app.requireUser);

  app.post('/families', async (request, reply) => {
    const input = parseInput(createFamilySchema, request.body);
    const family = await createFamily(app.prisma, request.authUser!.userId, input.name);
    return reply.code(201).send({ family });
  });

  app.post(
    '/families/join',
    // 6-digit codes are guessable; keep join attempts slow per client IP.
    { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } },
    async (request) => {
      const input = parseInput(joinFamilySchema, request.body);
      const family = await joinFamily(app.prisma, request.authUser!.userId, input.code);
      return { family };
    },
  );

  app.get('/families/me', async (request) => {
    return getFamilyOverview(app.prisma, request.authUser!.userId);
  });

  app.patch('/families/me/sharing', async (request) => {
    const input = parseInput(sharingSchema, request.body);
    // Resume resets rule baselines; serialize with ingest so an in-flight
    // evaluation cannot write the old state back.
    return app.alertMutex.run(() =>
      setSharingPaused(app.prisma, request.authUser!.userId, input.paused),
    );
  });

  app.post('/families/invite/reset', async (request) => {
    return resetInvite(app.prisma, request.authUser!.userId);
  });

  app.delete('/families/members/:userId', async (request, reply) => {
    const params = parseInput(userIdParam, request.params);
    await removeMember(app.prisma, request.authUser!.userId, params.userId);
    return reply.code(204).send();
  });

  app.post('/families/leave', async (request, reply) => {
    await leaveFamily(app.prisma, request.authUser!.userId);
    return reply.code(204).send();
  });
};
