import type { FastifyPluginAsync } from 'fastify';
import {
  createFollowSchema,
  createRuleSchema,
  followIdParams,
  parseInput,
  ruleParams,
  updateRuleSchema,
} from '../lib/validation.ts';
import {
  createFollow,
  createRule,
  deleteFollow,
  deleteRule,
  listFollowers,
  listFollows,
  updateRule,
} from '../services/follows.ts';

export const followRoutes: FastifyPluginAsync = async (app) => {
  app.addHook('preHandler', app.requireUser);

  app.get('/follows', async (request) => {
    const follows = await listFollows(app.prisma, request.authUser!.userId);
    return { follows };
  });

  app.post('/follows', async (request, reply) => {
    const input = parseInput(createFollowSchema, request.body);
    const { follow, created } = await createFollow(
      app.prisma,
      request.authUser!.userId,
      input.targetUserId,
      input.rules ?? [],
    );
    return reply.code(created ? 201 : 200).send({ follow });
  });

  // Static route: Fastify resolves it before /follows/:id.
  app.get('/follows/followers', async (request) => {
    const followers = await listFollowers(app.prisma, request.authUser!.userId);
    return { followers };
  });

  app.delete('/follows/:id', async (request, reply) => {
    const params = parseInput(followIdParams, request.params);
    await app.alertMutex.run(() =>
      deleteFollow(app.prisma, request.authUser!.userId, params.id),
    );
    return reply.code(204).send();
  });

  app.post('/follows/:id/rules', async (request, reply) => {
    const params = parseInput(followIdParams, request.params);
    const input = parseInput(createRuleSchema, request.body);
    const rule = await createRule(
      app.prisma,
      request.authUser!.userId,
      params.id,
      input.type,
      input.config,
    );
    return reply.code(201).send({ rule });
  });

  app.patch('/follows/:id/rules/:ruleId', async (request) => {
    const params = parseInput(ruleParams, request.params);
    const input = parseInput(updateRuleSchema, request.body);
    // Serialize with ingest: the evaluation may be writing this rule's state.
    const rule = await app.alertMutex.run(() =>
      updateRule(
        app.prisma,
        request.authUser!.userId,
        params.id,
        params.ruleId,
        input,
      ),
    );
    return { rule };
  });

  app.delete('/follows/:id/rules/:ruleId', async (request, reply) => {
    const params = parseInput(ruleParams, request.params);
    await app.alertMutex.run(() =>
      deleteRule(app.prisma, request.authUser!.userId, params.id, params.ruleId),
    );
    return reply.code(204).send();
  });
};
