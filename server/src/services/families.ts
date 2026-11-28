import { randomInt } from 'node:crypto';
import type { PrismaClient } from '@prisma/client';
import {
  alreadyInFamily,
  conflict,
  forbidden,
  inviteExpired,
  inviteInvalid,
  notFound,
  validationError,
} from '../lib/errors.ts';
import { isUniqueConstraintError } from '../lib/prisma.ts';
import { toLastLocation, type LastLocationDto } from '../lib/serializers.ts';
import { inviteExpiryFrom } from '../lib/time.ts';
import { resetRuleBaselines } from './alerts.ts';
import { cleanupFollowsForUser } from './follows.ts';
import { requireAdmin, requireMembership } from './memberships.ts';

export interface FamilyRoleDto {
  id: string;
  name: string;
  role: 'admin' | 'member';
}

export interface CreatedFamilyDto extends FamilyRoleDto {
  inviteCode: string;
  inviteExpiresAt: string;
}

export interface MemberOverviewDto {
  userId: string;
  username: string;
  nickname: string;
  role: string;
  sharingPaused: boolean;
  online: boolean;
  lastLocation: LastLocationDto | null;
}

export interface FamilyOverviewDto {
  family: {
    id: string;
    name: string;
    role: string;
    inviteCode?: string;
    inviteExpiresAt?: string;
  };
  members: MemberOverviewDto[];
}

export interface LatestMemberDto {
  userId: string;
  deviceId: string | null;
  online: boolean;
  lastSeenAt: string | null;
  lastLocation: LastLocationDto | null;
}

export interface InviteDto {
  inviteCode: string;
  expiresAt: string;
}

async function generateUniqueInviteCode(prisma: PrismaClient, forbiddenCode?: string): Promise<string> {
  for (let attempt = 0; attempt < 25; attempt += 1) {
    const code = randomInt(0, 1_000_000).toString().padStart(6, '0');
    if (forbiddenCode && code === forbiddenCode) continue;
    const existing = await prisma.family.findUnique({ where: { inviteCode: code }, select: { id: true } });
    if (!existing) return code;
  }
  throw conflict('Could not allocate an invite code, please retry');
}

export async function createFamily(
  prisma: PrismaClient,
  userId: string,
  name: string,
): Promise<CreatedFamilyDto> {
  const existing = await prisma.familyMember.findUnique({ where: { userId }, select: { id: true } });
  if (existing) throw alreadyInFamily();

  const inviteCode = await generateUniqueInviteCode(prisma);
  const inviteExpiresAt = inviteExpiryFrom();

  try {
    const family = await prisma.$transaction(async (tx) => {
      const created = await tx.family.create({
        data: { name, inviteCode, inviteExpiresAt },
      });
      await tx.familyMember.create({
        data: { familyId: created.id, userId, role: 'admin' },
      });
      return created;
    });

    return {
      id: family.id,
      name: family.name,
      inviteCode: family.inviteCode,
      inviteExpiresAt: family.inviteExpiresAt.toISOString(),
      role: 'admin',
    };
  } catch (error) {
    if (isUniqueConstraintError(error)) throw alreadyInFamily();
    throw error;
  }
}

export async function joinFamily(
  prisma: PrismaClient,
  userId: string,
  code: string,
): Promise<FamilyRoleDto> {
  const existing = await prisma.familyMember.findUnique({ where: { userId }, select: { id: true } });
  if (existing) throw alreadyInFamily();

  const family = await prisma.family.findUnique({ where: { inviteCode: code } });
  if (!family) throw inviteInvalid();
  if (family.inviteExpiresAt.getTime() <= Date.now()) throw inviteExpired();

  try {
    await prisma.familyMember.create({
      data: { familyId: family.id, userId, role: 'member' },
    });
  } catch (error) {
    if (isUniqueConstraintError(error)) throw alreadyInFamily();
    throw error;
  }

  return { id: family.id, name: family.name, role: 'member' };
}

export async function getFamilyOverview(
  prisma: PrismaClient,
  userId: string,
): Promise<FamilyOverviewDto> {
  const membership = await requireMembership(prisma, userId);

  const [members, devices] = await Promise.all([
    prisma.familyMember.findMany({
      where: { familyId: membership.familyId },
      include: { user: true },
      orderBy: { joinedAt: 'asc' },
    }),
    prisma.device.findMany({
      where: { familyId: membership.familyId, active: true },
      select: { userId: true, online: true },
    }),
  ]);

  const onlineUserIds = new Set(devices.filter((device) => device.online).map((device) => device.userId));

  const lastLocations = await Promise.all(
    members.map((member) =>
      prisma.location.findFirst({
        where: {
          userId: member.userId,
          familyId: membership.familyId,
          device: { active: true },
        },
        orderBy: { recordedAt: 'desc' },
      }),
    ),
  );

  return {
    family: {
      id: membership.family.id,
      name: membership.family.name,
      role: membership.role,
      // Invite details are only exposed to admins (contract §3).
      ...(membership.role === 'admin'
        ? {
            inviteCode: membership.family.inviteCode,
            inviteExpiresAt: membership.family.inviteExpiresAt.toISOString(),
          }
        : {}),
    },
    members: members.map((member, index) => ({
      userId: member.userId,
      username: member.user.username,
      nickname: member.user.nickname,
      role: member.role,
      sharingPaused: member.sharingPaused,
      online: onlineUserIds.has(member.userId),
      lastLocation: toLastLocation(lastLocations[index]),
    })),
  };
}

export async function resetInvite(
  prisma: PrismaClient,
  userId: string,
): Promise<InviteDto> {
  const membership = await requireMembership(prisma, userId);
  requireAdmin(membership);

  const inviteCode = await generateUniqueInviteCode(prisma, membership.family.inviteCode);
  const inviteExpiresAt = inviteExpiryFrom();

  await prisma.family.update({
    where: { id: membership.familyId },
    data: { inviteCode, inviteExpiresAt },
  });

  return { inviteCode, expiresAt: inviteExpiresAt.toISOString() };
}

export async function removeMember(
  prisma: PrismaClient,
  actorUserId: string,
  targetUserId: string,
): Promise<void> {
  const membership = await requireMembership(prisma, actorUserId);
  requireAdmin(membership);

  if (targetUserId === actorUserId) {
    throw validationError('Admins cannot remove themselves from the family');
  }

  const target = await prisma.familyMember.findFirst({
    where: { familyId: membership.familyId, userId: targetUserId },
  });
  if (!target) throw notFound('Family member not found');

  await prisma.$transaction(async (tx) => {
    // Soft-unbind: revoke device tokens and hide the devices, but keep rows so
    // the family retains historical tracks.
    await tx.device.updateMany({
      where: { familyId: membership.familyId, userId: targetUserId, active: true },
      data: { active: false, revokedAt: new Date(), online: false },
    });
    await tx.familyMember.delete({ where: { id: target.id } });
    await cleanupFollowsForUser(tx, targetUserId);
  });
}

export async function setSharingPaused(
  prisma: PrismaClient,
  userId: string,
  paused: boolean,
): Promise<{ sharingPaused: boolean }> {
  const membership = await requireMembership(prisma, userId);
  const updated = await prisma.familyMember.update({
    where: { id: membership.id },
    data: { sharingPaused: paused },
    select: { sharingPaused: true },
  });
  // Resume with fresh baselines: the pause gap is not a move or a fence trip.
  if (!paused) await resetRuleBaselines(prisma, userId);
  return { sharingPaused: updated.sharingPaused };
}

export async function leaveFamily(prisma: PrismaClient, userId: string): Promise<void> {
  const membership = await requireMembership(prisma, userId);
  if (membership.role === 'admin') {
    throw forbidden('Admins cannot leave the family in M1; transfer or dissolve is not supported');
  }

  await prisma.$transaction(async (tx) => {
    // Keep device rows (and Location history); only revoke access.
    await tx.device.updateMany({
      where: { familyId: membership.familyId, userId, active: true },
      data: { active: false, revokedAt: new Date(), online: false },
    });
    await tx.familyMember.delete({ where: { id: membership.id } });
    await cleanupFollowsForUser(tx, userId);
  });
}

export async function getFamilyLatest(
  prisma: PrismaClient,
  userId: string,
): Promise<{ members: LatestMemberDto[] }> {
  const membership = await requireMembership(prisma, userId);

  const [members, devices] = await Promise.all([
    prisma.familyMember.findMany({
      where: { familyId: membership.familyId },
      orderBy: { joinedAt: 'asc' },
    }),
    prisma.device.findMany({
      where: { familyId: membership.familyId, active: true },
      select: { id: true, userId: true, online: true, lastSeenAt: true, createdAt: true },
    }),
  ]);

  const devicesByUser = new Map<string, typeof devices>();
  for (const device of devices) {
    const list = devicesByUser.get(device.userId) ?? [];
    list.push(device);
    devicesByUser.set(device.userId, list);
  }

  const latest = await Promise.all(
    members.map(async (member) => {
      const memberDevices = devicesByUser.get(member.userId) ?? [];
      const sorted = [...memberDevices].sort((a, b) => {
        const aSeen = a.lastSeenAt ? a.lastSeenAt.getTime() : 0;
        const bSeen = b.lastSeenAt ? b.lastSeenAt.getTime() : 0;
        if (aSeen !== bSeen) return bSeen - aSeen;
        return b.createdAt.getTime() - a.createdAt.getTime();
      });
      const primary = sorted[0];

      const lastLocation = await prisma.location.findFirst({
        where: {
          userId: member.userId,
          familyId: membership.familyId,
          device: { active: true },
        },
        orderBy: { recordedAt: 'desc' },
      });

      const lastSeen = sorted.reduce<Date | null>((current, device) => {
        if (!device.lastSeenAt) return current;
        if (!current) return device.lastSeenAt;
        return device.lastSeenAt.getTime() > current.getTime() ? device.lastSeenAt : current;
      }, null);

      return {
        userId: member.userId,
        deviceId: primary ? primary.id : null,
        online: memberDevices.some((device) => device.online),
        lastSeenAt: lastSeen ? lastSeen.toISOString() : null,
        lastLocation: toLastLocation(lastLocation),
      } satisfies LatestMemberDto;
    }),
  );

  return { members: latest };
}
