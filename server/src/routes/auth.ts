import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { unauthorized } from '../lib/errors.ts';
import { toDeviceDto, toUserDto } from '../lib/serializers.ts';
import { parseInput } from '../lib/validation.ts';
import { authenticateUser, registerUser } from '../services/auth.ts';

const registerSchema = z.object({
  username: z.string().trim().min(3).max(20).regex(/^\S+$/, 'Username must not contain spaces'),
  nickname: z.string().trim().min(1).max(50),
  password: z.string().min(8).max(200),
  inviteCode: z
    .string()
    .trim()
    .regex(/^\d{6}$/, 'Invite code must be 6 digits')
    .optional(),
});

const loginSchema = z.object({
  username: z.string().trim().min(1).max(50),
  password: z.string().min(1).max(200),
});

const authRateLimit = {
  rateLimit: { max: 20, timeWindow: '1 minute' },
} as const;

export const authRoutes: FastifyPluginAsync = async (app) => {
  app.post('/auth/register', { config: authRateLimit }, async (request, reply) => {
    const input = parseInput(registerSchema, request.body);
    const result = await registerUser(app.prisma, input);
    const token = app.jwt.sign({ sub: result.user.id });

    return reply.code(201).send({
      token,
      user: result.user,
      familyId: result.familyId,
    });
  });

  app.post('/auth/login', { config: authRateLimit }, async (request) => {
    const input = parseInput(loginSchema, request.body);
    const result = await authenticateUser(app.prisma, input.username, input.password);
    const token = app.jwt.sign({ sub: result.user.id });

    return {
      token,
      user: result.user,
      familyId: result.familyId,
    };
  });

  app.get('/me', { preHandler: app.requireUser }, async (request) => {
    const userId = request.authUser!.userId;

    const user = await app.prisma.user.findUnique({ where: { id: userId } });
    if (!user) throw unauthorized('User no longer exists');

    const [membership, devices] = await Promise.all([
      app.prisma.familyMember.findUnique({
        where: { userId },
        include: { family: true },
      }),
      app.prisma.device.findMany({
        where: { userId, active: true },
        include: { locations: { orderBy: { recordedAt: 'desc' }, take: 1 } },
        orderBy: { createdAt: 'asc' },
      }),
    ]);

    return {
      user: toUserDto(user),
      family: membership
        ? { id: membership.family.id, name: membership.family.name, role: membership.role }
        : null,
      devices: devices.map((device) => toDeviceDto(device, device.locations[0])),
    };
  });
};
