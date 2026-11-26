import type { FastifyPluginAsync } from 'fastify';
import { idParam, parseInput } from '../lib/validation.ts';
import { getCommand, requestLocate } from '../services/locate.ts';

export const locateRoutes: FastifyPluginAsync = async (app) => {
  app.addHook('preHandler', app.requireUser);

  app.post('/devices/:id/locate', async (request, reply) => {
    const params = parseInput(idParam, request.params);
    const result = await requestLocate(
      {
        prisma: app.prisma,
        realtime: app.realtime,
        locateTimeoutMs: app.config.locateTimeoutMs,
        mutex: app.locateMutex,
      },
      request.authUser!.userId,
      params.id,
    );

    // 200 when an in-flight command was reused; 202 when a new command was created.
    return reply.code(result.deduped ? 200 : 202).send({
      commandId: result.commandId,
      deadlineAt: result.deadlineAt,
    });
  });

  app.get('/commands/:id', async (request) => {
    const params = parseInput(idParam, request.params);
    return getCommand(app.prisma, request.authUser!.userId, params.id);
  });
};
