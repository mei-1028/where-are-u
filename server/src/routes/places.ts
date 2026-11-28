import type { FastifyPluginAsync } from 'fastify';
import {
  createPlaceSchema,
  idParam,
  parseInput,
  updatePlaceSchema,
} from '../lib/validation.ts';
import {
  createPlace,
  deletePlace,
  listPlaces,
  updatePlace,
} from '../services/places.ts';

export const placeRoutes: FastifyPluginAsync = async (app) => {
  app.addHook('preHandler', app.requireUser);

  app.get('/places', async (request) => {
    const places = await listPlaces(app.prisma, request.authUser!.userId);
    return { places };
  });

  app.post('/places', async (request, reply) => {
    const input = parseInput(createPlaceSchema, request.body);
    const place = await createPlace(
      app.prisma,
      request.authUser!.userId,
      input,
    );
    return reply.code(201).send({ place });
  });

  app.patch('/places/:id', async (request) => {
    const params = parseInput(idParam, request.params);
    const input = parseInput(updatePlaceSchema, request.body);
    // Serialize with location ingest so a rule state cannot be written back
    // after a place reset.
    const place = await app.alertMutex.run(() =>
      updatePlace(app.prisma, request.authUser!.userId, params.id, input),
    );
    return { place };
  });

  app.delete('/places/:id', async (request) => {
    const params = parseInput(idParam, request.params);
    return app.alertMutex.run(() =>
      deletePlace(app.prisma, request.authUser!.userId, params.id),
    );
  });
};
