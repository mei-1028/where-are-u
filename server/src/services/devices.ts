import type { Device, PrismaClient } from '@prisma/client';
import { generateDeviceToken } from '../lib/crypto.ts';
import { conflict, forbidden, notFound } from '../lib/errors.ts';
import { toDeviceDto, type DeviceDto } from '../lib/serializers.ts';
import { requireMembership, type MembershipWithFamily } from './memberships.ts';

export interface RegisterDeviceInput {
  name: string;
  platform: string;
  model: string;
  osVersion: string;
  appVersion: string;
}

export interface RegisterDeviceResult {
  device: DeviceDto;
  deviceToken: string;
}

function includeLatestLocation() {
  return {
    locations: {
      orderBy: { recordedAt: 'desc' as const },
      take: 1,
    },
  };
}

export async function registerDevice(
  prisma: PrismaClient,
  userId: string,
  input: RegisterDeviceInput,
): Promise<RegisterDeviceResult> {
  const membership = await prisma.familyMember.findUnique({ where: { userId } });
  if (!membership) throw conflict('Join or create a family before registering a device');

  const { token, tokenHash } = generateDeviceToken();
  const device = await prisma.device.create({
    data: {
      userId,
      familyId: membership.familyId,
      name: input.name,
      platform: input.platform,
      model: input.model,
      osVersion: input.osVersion,
      appVersion: input.appVersion,
      tokenHash,
    },
  });

  // The plaintext token is returned exactly once.
  return { device: toDeviceDto(device), deviceToken: token };
}

export async function listUserDevices(prisma: PrismaClient, userId: string): Promise<DeviceDto[]> {
  const devices = await prisma.device.findMany({
    where: { userId, active: true },
    include: includeLatestLocation(),
    orderBy: { createdAt: 'asc' },
  });
  return devices.map((device) => toDeviceDto(device, device.locations[0]));
}

export async function listFamilyDevices(prisma: PrismaClient, userId: string): Promise<DeviceDto[]> {
  const membership = await requireMembership(prisma, userId);
  const devices = await prisma.device.findMany({
    where: { familyId: membership.familyId, active: true },
    include: includeLatestLocation(),
    orderBy: { createdAt: 'asc' },
  });
  return devices.map((device) => toDeviceDto(device, device.locations[0]));
}

interface DeviceAccess {
  device: Device;
  membership: MembershipWithFamily;
}

/**
 * Loads a device that belongs to the caller's family. Cross-family access is
 * reported as 404 so resource existence is not leaked. Same-family members
 * that are not the owner still get 403 for owner/admin-only actions.
 *
 * Inactive (revoked) devices are excluded by default, so management, locate
 * and token-driven flows cannot touch them. History reads may opt in to keep
 * historical tracks queryable after a device is unbound.
 */
export async function getFamilyDeviceAccess(
  prisma: PrismaClient,
  userId: string,
  deviceId: string,
  options: { allowInactive?: boolean } = {},
): Promise<DeviceAccess> {
  const membership = await requireMembership(prisma, userId);
  const device = await prisma.device.findFirst({
    where: {
      id: deviceId,
      familyId: membership.familyId,
      ...(options.allowInactive ? {} : { active: true }),
    },
  });
  if (!device) throw notFound('Device not found');
  return { device, membership };
}

function assertCanManageDevice(access: DeviceAccess, userId: string): void {
  if (access.device.userId !== userId && access.membership.role !== 'admin') {
    throw forbidden('Only the device owner or a family admin can manage this device');
  }
}

export async function renameDevice(
  prisma: PrismaClient,
  userId: string,
  deviceId: string,
  name: string,
): Promise<DeviceDto> {
  const access = await getFamilyDeviceAccess(prisma, userId, deviceId);
  assertCanManageDevice(access, userId);

  const device = await prisma.device.update({
    where: { id: access.device.id },
    data: { name },
    include: includeLatestLocation(),
  });
  return toDeviceDto(device, device.locations[0]);
}

export async function deleteDevice(
  prisma: PrismaClient,
  userId: string,
  deviceId: string,
): Promise<void> {
  const access = await getFamilyDeviceAccess(prisma, userId, deviceId);
  assertCanManageDevice(access, userId);

  // Soft unbind: keep the device row and its Location history, but revoke all
  // future authentication and hide it from active device lists.
  await prisma.device.update({
    where: { id: access.device.id },
    data: { active: false, revokedAt: new Date(), online: false },
  });
}
