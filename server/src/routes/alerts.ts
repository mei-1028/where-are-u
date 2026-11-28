import type { FastifyPluginAsync } from 'fastify';
import { alertsQuerySchema, followIdParams, parseInput } from '../lib/validation.ts';
import {
  listAlerts,
  markAlertRead,
  markAllAlertsRead,
} from '../services/alerts.ts';

export const alertRoutes: FastifyPluginAsync = async (app) => {
  app.addHook('preHandler', app.requireUser);

  app.get('/alerts', async (request) => {
    const query = parseInput(alertsQuerySchema, request.query);
    return listAlerts(app.prisma, request.authUser!.userId, {
      limit: query.limit,
      unreadOnly: query.unread === '1',
      ...(query.after ? { after: new Date(query.after) } : {}),
      ...(query.before ? { before: new Date(query.before) } : {}),
    });
  });

  // Static route before /alerts/:id/read is unnecessary (different depth), but
  // keep the read-all endpoint registered first for clarity.
  app.post('/alerts/read-all', async (request) => {
    const updated = await markAllAlertsRead(app.prisma, request.authUser!.userId);
    return { updated };
  });

  app.post('/alerts/:id/read', async (request, reply) => {
    const params = parseInput(followIdParams, request.params);
    await markAlertRead(app.prisma, request.authUser!.userId, params.id);
    return reply.code(204).send();
  });
};
