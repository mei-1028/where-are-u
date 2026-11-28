import type { FastifyPluginAsync } from 'fastify';
import { validationError } from '../lib/errors.ts';
import { MAX_RECORDED_AT_FUTURE_MS } from '../lib/time.ts';
import { evaluateIngest } from '../services/alerts.ts';
import { getFamilyLatest } from '../services/families.ts';
import { ingestLocations, listDeviceHistory } from '../services/locations.ts';
import {
  historyQuerySchema,
  idParam,
  ingestSchema,
  parseDate,
  parseInput,
} from '../lib/validation.ts';

export const locationRoutes: FastifyPluginAsync = async (app) => {
  // Device-token authenticated ingest.
  app.post(
    '/locations',
    { preHandler: app.requireDevice },
    async (request, reply) => {
      const input = parseInput(ingestSchema, request.body);

      const now = Date.now();
      const tooFarInFuture = input.points.find(
        (point) => Date.parse(point.recordedAt) > now + MAX_RECORDED_AT_FUTURE_MS,
      );
      if (tooFarInFuture) {
        // Deliberately log only device + timestamp, never the full location.
        request.log.warn(
          {
            deviceId: request.authDevice!.deviceId,
            recordedAt: tooFarInFuture.recordedAt,
          },
          'Rejected location batch: recordedAt is too far in the future',
        );
        throw validationError(
          `points: recordedAt must not be more than ${MAX_RECORDED_AT_FUTURE_MS / 60_000} minutes in the future`,
        );
      }

      const accepted = await ingestLocations(app.prisma, request.authDevice!, input);

      // Alerts are best-effort: a rule bug must never fail a location write.
      try {
        await app.alertMutex.run(() =>
          evaluateIngest(
            app.prisma,
            app.realtime,
            request.authDevice!.userId,
            input.points,
          ),
        );
      } catch (error) {
        request.log.warn({ err: error }, 'Alert evaluation failed');
      }

      return reply.code(202).send({ accepted });
    },
  );

  // Family-scoped history for one device.
  app.get(
    '/devices/:id/locations',
    { preHandler: app.requireUser },
    async (request) => {
      const params = parseInput(idParam, request.params);
      const query = parseInput(historyQuerySchema, request.query);

      const points = await listDeviceHistory(
        app.prisma,
        request.authUser!.userId,
        params.id,
        {
          ...(query.from ? { from: parseDate(query.from) } : {}),
          ...(query.to ? { to: parseDate(query.to) } : {}),
          limit: query.limit,
        },
      );
      return { points };
    },
  );

  // Polling endpoint for the family map.
  app.get(
    '/families/me/latest',
    { preHandler: app.requireUser },
    async (request) => {
      return getFamilyLatest(app.prisma, request.authUser!.userId);
    },
  );
};
