import cors from '@fastify/cors';
import rateLimit from '@fastify/rate-limit';
import fp from 'fastify-plugin';
import { parseCorsOrigin } from '../lib/config.ts';
import { AppError } from '../lib/errors.ts';

export const securityPlugin = fp(
  async (app) => {
    await app.register(cors, {
      origin: parseCorsOrigin(app.config.corsOrigin),
      methods: ['GET', 'POST', 'PATCH', 'DELETE', 'OPTIONS'],
      allowedHeaders: ['Content-Type', 'Authorization'],
      maxAge: 600,
    });

    // Broad global umbrella; auth routes add a stricter per-route limit.
    await app.register(rateLimit, {
      global: true,
      max: 600,
      timeWindow: '1 minute',
      // The plugin throws the returned error; the global error handler then
      // renders it using the uniform contract error shape.
      errorResponseBuilder: () =>
        new AppError(429, 'RATE_LIMITED', 'Too many requests, please try again later'),
    });
  },
  { name: 'security' },
);
