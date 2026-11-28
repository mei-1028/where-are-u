import type { PrismaClient } from '@prisma/client';
import type { FastifyRequest } from 'fastify';
import type { AppConfig } from '../lib/config.ts';
import type { Mutex } from '../lib/mutex.ts';
import type { RealtimeHub } from '../services/realtime.ts';

export interface AuthUser {
  userId: string;
}

export interface AuthDevice {
  deviceId: string;
  userId: string;
  familyId: string;
}

declare module 'fastify' {
  interface FastifyRequest {
    authUser: AuthUser | null;
    authDevice: AuthDevice | null;
  }

  interface FastifyInstance {
    prisma: PrismaClient;
    config: AppConfig;
    realtime: RealtimeHub;
    /** Serializes locate dedupe + creation within this process. */
    locateMutex: Mutex;
    /** Serializes alert evaluation (ingest + stale sweep) within this process. */
    alertMutex: Mutex;
    requireUser: (request: FastifyRequest) => Promise<void>;
    requireDevice: (request: FastifyRequest) => Promise<void>;
  }
}
