import 'dotenv/config';
import { buildServer } from './app.ts';
import { loadConfig, loggerOptions } from './lib/config.ts';

async function main(): Promise<void> {
  const config = loadConfig();
  const app = await buildServer({
    config,
    logger: loggerOptions(config.logLevel),
  });

  // Presence flags from a previous process are stale until devices reconnect.
  const stale = await app.prisma.device.updateMany({
    where: { online: true },
    data: { online: false },
  });
  if (stale.count > 0) {
    app.log.info({ count: stale.count }, 'Reset stale device presence on startup');
  }

  await app.listen({ port: config.port, host: '0.0.0.0' });

  let shuttingDown = false;
  const shutdown = (signal: string): void => {
    if (shuttingDown) return;
    shuttingDown = true;
    app.log.info({ signal }, 'Shutting down');
    app
      .close()
      .then(() => process.exit(0))
      .catch((error: unknown) => {
        app.log.error({ err: error }, 'Graceful shutdown failed');
        process.exit(1);
      });
  };

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

main().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : String(error);
  console.error(`[startup] ${message}`);
  process.exit(1);
});
