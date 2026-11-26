import type { PrismaClient } from '@prisma/client';
import { validationError } from '../lib/errors.ts';
import { startOfTodayUtc } from '../lib/validation.ts';
import { getFamilyDeviceAccess } from './devices.ts';

export interface DeviceAuthContext {
  deviceId: string;
  userId: string;
  familyId: string;
}

export interface IngestPoint {
  lat: number;
  lng: number;
  accuracy: number;
  speed?: number;
  bearing?: number;
  battery?: number;
  provider?: string;
  recordedAt: string;
}

export interface IngestInput {
  commandId?: string;
  points: IngestPoint[];
}

export interface LocationPointDto {
  id: string;
  lat: number;
  lng: number;
  accuracy: number;
  speed: number | null;
  bearing: number | null;
  battery: number | null;
  provider: string | null;
  recordedAt: string;
}

export interface HistoryOptions {
  from?: Date;
  to?: Date;
  limit: number;
}

/**
 * Batch ingest: writes all points, refreshes the device heartbeat and, when a
 * commandId is supplied, completes that pending LocateCommand. Returns the
 * number of accepted points.
 */
export async function ingestLocations(
  prisma: PrismaClient,
  device: DeviceAuthContext,
  input: IngestInput,
): Promise<number> {
  const now = new Date();
  const newestRecordedAtMs = input.points.reduce(
    (newest, point) => Math.max(newest, Date.parse(point.recordedAt)),
    Number.NEGATIVE_INFINITY,
  );

  await prisma.$transaction(async (tx) => {
    await tx.location.createMany({
      data: input.points.map((point) => ({
        deviceId: device.deviceId,
        userId: device.userId,
        familyId: device.familyId,
        lat: point.lat,
        lng: point.lng,
        accuracy: Math.round(point.accuracy),
        speed: point.speed ?? null,
        bearing: point.bearing ?? null,
        battery: point.battery ?? null,
        provider: point.provider ?? null,
        recordedAt: new Date(point.recordedAt),
      })),
    });

    await tx.device.update({
      where: { id: device.deviceId },
      data: { lastSeenAt: now },
    });

    if (input.commandId) {
      const command = await tx.locateCommand.findFirst({
        where: {
          id: input.commandId,
          deviceId: device.deviceId,
          status: 'pending',
        },
        select: { id: true, createdAt: true },
      });

      // A batch made only of points recorded before the command was issued
      // must not complete it; wait for a fresh fix (or the timeout sweep).
      if (command && newestRecordedAtMs >= command.createdAt.getTime()) {
        await tx.locateCommand.updateMany({
          where: {
            id: command.id,
            deviceId: device.deviceId,
            status: 'pending',
          },
          data: { status: 'completed', completedAt: now },
        });
      }
    }
  });

  return input.points.length;
}

export async function listDeviceHistory(
  prisma: PrismaClient,
  userId: string,
  deviceId: string,
  options: HistoryOptions,
): Promise<LocationPointDto[]> {
  // History survives device revocation: any current family member may still
  // read a revoked device's track, while management/locate paths stay blocked.
  await getFamilyDeviceAccess(prisma, userId, deviceId, { allowInactive: true });

  const from = options.from ?? startOfTodayUtc();
  const to = options.to ?? new Date();
  if (from.getTime() > to.getTime()) {
    throw validationError('`from` must be earlier than or equal to `to`');
  }

  const points = await prisma.location.findMany({
    where: {
      deviceId,
      recordedAt: { gte: from, lte: to },
    },
    orderBy: { recordedAt: 'asc' },
    take: Math.min(options.limit, 5000),
  });

  return points.map((point) => ({
    id: point.id,
    lat: point.lat,
    lng: point.lng,
    accuracy: point.accuracy,
    speed: point.speed,
    bearing: point.bearing,
    battery: point.battery,
    provider: point.provider,
    recordedAt: point.recordedAt.toISOString(),
  }));
}
