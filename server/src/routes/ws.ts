import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';

export interface WsRoutesOptions {
  heartbeatIntervalMs: number;
  silenceTimeoutMs: number;
}

const clientMessageSchema = z.union([
  z.object({
    type: z.literal('hello'),
    appVersion: z.string().min(1).max(32).optional(),
    battery: z.number().int().min(0).max(100).optional(),
  }),
  z.object({
    type: z.literal('pong'),
    ts: z.string().optional(),
  }),
]);

/**
 * Device WebSocket endpoint. Authentication happens in preValidation, i.e.
 * before the HTTP upgrade, using the same device token as location ingest.
 *
 * Server -> device: { type: "locate", commandId, deadlineAt }, { type: "ping", ts }
 * Device -> server: { type: "pong", ts }, { type: "hello", appVersion, battery }
 *
 * The server pings every 30s and drops a connection after 90s of silence.
 */
export const wsRoutes: FastifyPluginAsync<WsRoutesOptions> = async (app, options) => {
  const { heartbeatIntervalMs, silenceTimeoutMs } = options;

  app.get(
    '/ws',
    {
      websocket: true,
      config: { rateLimit: false },
      preValidation: app.requireDevice,
    },
    (socket, request) => {
      const auth = request.authDevice;
      if (!auth) {
        // Should be unreachable thanks to preValidation; close defensively.
        socket.close(1008, 'Unauthorized');
        return;
      }

      const deviceId = auth.deviceId;
      let lastActivity = Date.now();

      app.realtime.attach(deviceId, socket);

      const touchDevice = (data: { appVersion?: string } = {}) => {
        // Guard with active=true so a device revoked while its socket is still
        // open can never re-enter the active/online set.
        app.prisma.device
          .updateMany({
            where: { id: deviceId, active: true },
            data: {
              online: true,
              lastSeenAt: new Date(),
              ...(data.appVersion ? { appVersion: data.appVersion } : {}),
            },
          })
          .catch((error: unknown) => {
            request.log.warn({ err: error }, 'Failed to update device presence');
          });
      };

      // Connection established -> online=true.
      touchDevice();

      const heartbeat = setInterval(() => {
        if (Date.now() - lastActivity > silenceTimeoutMs) {
          clearInterval(heartbeat);
          try {
            socket.close(4002, 'Heartbeat timeout');
          } catch {
            // Socket is already gone.
          }
          return;
        }

        if (socket.readyState === 1) {
          try {
            socket.send(JSON.stringify({ type: 'ping', ts: new Date().toISOString() }));
          } catch {
            // The close handler will clean up.
          }
        }
      }, heartbeatIntervalMs);
      heartbeat.unref();

      socket.on('message', (raw) => {
        lastActivity = Date.now();

        let parsed: unknown;
        try {
          parsed = JSON.parse(raw.toString());
        } catch {
          return;
        }

        const message = clientMessageSchema.safeParse(parsed);
        if (!message.success) return;

        if (message.data.type === 'hello') {
          touchDevice(
            message.data.appVersion ? { appVersion: message.data.appVersion } : {},
          );
        } else {
          // pong: refresh heartbeat / last seen.
          touchDevice();
        }
      });

      socket.on('error', (error) => {
        request.log.debug({ err: error }, 'Device WebSocket error');
      });

      socket.on('close', () => {
        clearInterval(heartbeat);
        const wasCurrent = app.realtime.detach(deviceId, socket);
        if (!wasCurrent) return; // A newer connection already took over.

        app.prisma.device
          .update({
            where: { id: deviceId },
            data: { online: false, lastSeenAt: new Date() },
          })
          .catch((error: unknown) => {
            request.log.warn({ err: error }, 'Failed to update device presence');
          });
      });
    },
  );
};
