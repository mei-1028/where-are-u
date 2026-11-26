import type { PrismaClient } from '@prisma/client';
import { deviceOffline, notFound } from '../lib/errors.ts';
import type { Mutex } from '../lib/mutex.ts';
import { addMilliseconds, LOCATE_DEDUPE_MS } from '../lib/time.ts';
import { requireMembership } from './memberships.ts';
import type { RealtimeHub } from './realtime.ts';

export type LocateCommandStatus = 'pending' | 'completed' | 'timeout' | 'failed';

export interface LocateRequestResult {
  commandId: string;
  deadlineAt: string;
  deduped: boolean;
}

export interface LocateCommandDto {
  status: LocateCommandStatus;
  completedAt?: string;
}

export interface LocateDeps {
  prisma: PrismaClient;
  realtime: RealtimeHub;
  locateTimeoutMs: number;
  /** Serializes the dedupe check and command creation (single-process M1). */
  mutex: Mutex;
}

/**
 * Creates (or reuses) a "locate now" command and pushes it to the device over
 * its WebSocket. Requests for the same device within 30 seconds are merged.
 */
export async function requestLocate(
  deps: LocateDeps,
  userId: string,
  deviceId: string,
): Promise<LocateRequestResult> {
  const { prisma, realtime, mutex } = deps;
  const membership = await requireMembership(prisma, userId);

  const device = await prisma.device.findFirst({
    where: { id: deviceId, familyId: membership.familyId, active: true },
  });
  if (!device) throw notFound('Device not found');

  const online = device.online || realtime.isConnected(device.id);
  if (!online) throw deviceOffline();

  // Two concurrent requests would otherwise both observe "no pending command"
  // and create duplicates. Single-instance M1 uses an in-process mutex.
  return mutex.run(async () => {
    const now = new Date();
    const existing = await prisma.locateCommand.findFirst({
      where: {
        deviceId: device.id,
        status: 'pending',
        createdAt: { gte: addMilliseconds(now, -LOCATE_DEDUPE_MS) },
        deadlineAt: { gt: now },
      },
      orderBy: { createdAt: 'desc' },
    });

    if (existing) {
      return {
        commandId: existing.id,
        deadlineAt: existing.deadlineAt.toISOString(),
        deduped: true,
      };
    }

    const deadlineAt = addMilliseconds(now, deps.locateTimeoutMs);
    const command = await prisma.locateCommand.create({
      data: {
        deviceId: device.id,
        familyId: device.familyId,
        requestedBy: userId,
        status: 'pending',
        deadlineAt,
      },
    });

    const sent = realtime.send(device.id, {
      type: 'locate',
      commandId: command.id,
      deadlineAt: deadlineAt.toISOString(),
    });

    if (!sent) {
      // Device went away between the online check and the push.
      await prisma.locateCommand.update({
        where: { id: command.id },
        data: { status: 'failed' },
      });
      throw deviceOffline();
    }

    return { commandId: command.id, deadlineAt: deadlineAt.toISOString(), deduped: false };
  });
}

export async function getCommand(
  prisma: PrismaClient,
  userId: string,
  commandId: string,
): Promise<LocateCommandDto> {
  const membership = await requireMembership(prisma, userId);
  const command = await prisma.locateCommand.findFirst({
    where: { id: commandId, familyId: membership.familyId },
  });
  if (!command) throw notFound('Command not found');

  return {
    status: command.status as LocateCommandStatus,
    ...(command.completedAt ? { completedAt: command.completedAt.toISOString() } : {}),
  };
}

/** Marks pending commands past their deadline as timed out. */
export async function sweepExpiredCommands(
  prisma: PrismaClient,
  now = new Date(),
): Promise<number> {
  const result = await prisma.locateCommand.updateMany({
    where: { status: 'pending', deadlineAt: { lte: now } },
    data: { status: 'timeout' },
  });
  return result.count;
}
