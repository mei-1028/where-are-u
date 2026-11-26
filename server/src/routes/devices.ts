import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { idParam, parseInput } from '../lib/validation.ts';
import {
  deleteDevice,
  listFamilyDevices,
  registerDevice,
  renameDevice,
} from '../services/devices.ts';

const registerDeviceSchema = z.object({
  name: z.string().trim().min(1).max(50),
  platform: z.string().trim().min(1).max(32),
  model: z.string().trim().min(1).max(64),
  osVersion: z.string().trim().min(1).max(64),
  appVersion: z.string().trim().min(1).max(32),
});

const renameDeviceSchema = z.object({
  name: z.string().trim().min(1).max(50),
});

export const deviceRoutes: FastifyPluginAsync = async (app) => {
  app.addHook('preHandler', app.requireUser);

  app.post('/devices', async (request, reply) => {
    const input = parseInput(registerDeviceSchema, request.body);
    const result = await registerDevice(app.prisma, request.authUser!.userId, input);
    return reply.code(201).send(result);
  });

  app.get('/devices', async (request) => {
    const devices = await listFamilyDevices(app.prisma, request.authUser!.userId);
    return { devices };
  });

  app.patch('/devices/:id', async (request) => {
    const params = parseInput(idParam, request.params);
    const input = parseInput(renameDeviceSchema, request.body);
    const device = await renameDevice(
      app.prisma,
      request.authUser!.userId,
      params.id,
      input.name,
    );
    return { device };
  });

  app.delete('/devices/:id', async (request, reply) => {
    const params = parseInput(idParam, request.params);
    await deleteDevice(app.prisma, request.authUser!.userId, params.id);
    return reply.code(204).send();
  });
};
