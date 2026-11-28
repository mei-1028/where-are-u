import websocket from '@fastify/websocket';
import Fastify, { type FastifyInstance, type FastifyServerOptions } from 'fastify';
import { type AppConfig, type ConfigOverrides, loadConfig } from './lib/config.ts';
import { Mutex } from './lib/mutex.ts';
import { createPrismaClient } from './lib/prisma.ts';
import { authPlugin } from './plugins/auth.ts';
import { errorHandlerPlugin } from './plugins/errors.ts';
import { securityPlugin } from './plugins/security.ts';
import { alertRoutes } from './routes/alerts.ts';
import { authRoutes } from './routes/auth.ts';
import { deviceRoutes } from './routes/devices.ts';
import { familyRoutes } from './routes/families.ts';
import { followRoutes } from './routes/follows.ts';
import { healthRoutes } from './routes/health.ts';
import { locateRoutes } from './routes/locate.ts';
import { locationRoutes } from './routes/locations.ts';
import { placeRoutes } from './routes/places.ts';
import { wsRoutes } from './routes/ws.ts';
import { sweepAlerts } from './services/alerts.ts';
import { RealtimeHub } from './services/realtime.ts';
import { sweepExpiredCommands } from './services/locate.ts';

export interface BuildServerOptions {
  config?: ConfigOverrides;
  logger?: FastifyServerOptions['logger'];
  /** Test hook; production behaviour is 30s. */
  heartbeatIntervalMs?: number;
  /** Test hook; production behaviour is 90s. */
  wsSilenceTimeoutMs?: number;
  /** Test hook; production sweeper runs every 5s. */
  sweepIntervalMs?: number;
  /** Test hook; the stale-alert sweep runs every 60s in production. */
  alertSweepIntervalMs?: number;
}

export async function buildServer(options: BuildServerOptions = {}): Promise<FastifyInstance> {
  const config: AppConfig = loadConfig(options.config);

  const app = Fastify({
    logger: options.logger ?? false,
    trustProxy: config.trustProxy,
  });

  const prisma = createPrismaClient(config.databaseUrl);
  app.decorate('prisma', prisma);
  app.decorate('config', config);
  app.decorate('realtime', new RealtimeHub());
  app.decorate('locateMutex', new Mutex());
  // Serializes alert evaluation so two devices of the same member cannot race
  // the same rule state.
  app.decorate('alertMutex', new Mutex());

  await app.register(errorHandlerPlugin);
  await app.register(authPlugin);
  await app.register(securityPlugin);
  // Device messages are tiny; cap frames to reduce abuse potential.
  await app.register(websocket, { options: { maxPayload: 64 * 1024 } });

  await app.register(healthRoutes);

  await app.register(
    async (api) => {
      await api.register(authRoutes);
      await api.register(familyRoutes);
      await api.register(deviceRoutes);
      await api.register(locationRoutes);
      await api.register(locateRoutes);
      await api.register(followRoutes);
      await api.register(alertRoutes);
      await api.register(placeRoutes);
    },
    { prefix: '/api' },
  );

  await app.register(wsRoutes, {
    heartbeatIntervalMs: options.heartbeatIntervalMs ?? 30_000,
    silenceTimeoutMs: options.wsSilenceTimeoutMs ?? 90_000,
  });

  // Periodic timeout sweep for pending locate commands.
  const sweepIntervalMs = options.sweepIntervalMs ?? 5_000;
  const sweepTimer = setInterval(() => {
    sweepExpiredCommands(prisma).catch((error: unknown) => {
      app.log.error({ err: error }, 'Locate command timeout sweep failed');
    });
  }, sweepIntervalMs);
  sweepTimer.unref();

  // Stale-alert sweep (fires "no update for N hours") + alert retention.
  const alertSweepIntervalMs = options.alertSweepIntervalMs ?? 60_000;
  const alertSweepTimer = setInterval(() => {
    app.alertMutex
      .run(() => sweepAlerts(prisma, app.realtime))
      .catch((error: unknown) => {
        app.log.error({ err: error }, 'Alert sweep failed');
      });
  }, alertSweepIntervalMs);
  alertSweepTimer.unref();

  app.addHook('onClose', async () => {
    clearInterval(sweepTimer);
    clearInterval(alertSweepTimer);
    app.realtime.closeAll();
    await prisma.$disconnect();
  });

  return app;
}
