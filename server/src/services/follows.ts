import type { AlertRule, Prisma, PrismaClient } from '@prisma/client';
import { conflict, notFound, validationError } from '../lib/errors.ts';
import { isUniqueConstraintError } from '../lib/prisma.ts';
import {
  parseRuleConfig,
  type RuleConfig,
  type RuleType,
} from '../lib/validation.ts';
import { requireMembership } from './memberships.ts';

export interface FollowTargetDto {
  userId: string;
  nickname: string;
}

export interface AlertRuleDto {
  id: string;
  type: RuleType;
  enabled: boolean;
  config: RuleConfig;
  lastFiredAt: string | null;
}

export interface FollowDto {
  id: string;
  target: FollowTargetDto;
  createdAt: string;
  rules: AlertRuleDto[];
}

export interface FollowerDto {
  userId: string;
  nickname: string;
  since: string;
}

const MAX_RULES_PER_FOLLOW = 5;

/** Evaluation state is opaque to clients; only `lastFiredAt` is surfaced. */
interface RuleStateLike {
  lastFiredAt?: string | null;
  armed?: boolean;
  anchorLat?: number | null;
  anchorLng?: number | null;
  lastRecordedAtMs?: number | null;
}

export function parseState(raw: string): RuleStateLike {
  try {
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? (parsed as RuleStateLike) : {};
  } catch {
    return {};
  }
}

export function defaultState(type: RuleType): Record<string, unknown> {
  switch (type) {
    case 'MOVEMENT':
      return { anchorLat: null, anchorLng: null, lastFiredAt: null, lastRecordedAtMs: null };
    case 'STALE':
      // Armed only once a fresh location arrives: following somebody who has
      // already been offline for days must not fire immediately.
      return { armed: false, lastFiredAt: null };
    case 'LOW_BATTERY':
      return { armed: true, lastFiredAt: null };
  }
}

function toRuleDto(rule: AlertRule): AlertRuleDto {
  const state = parseState(rule.state);
  const type = rule.type as RuleType;
  let config: RuleConfig;
  try {
    config = parseRuleConfig(type, JSON.parse(rule.config));
  } catch {
    config = parseRuleConfig(type, {});
  }
  return {
    id: rule.id,
    type,
    enabled: rule.enabled,
    config,
    lastFiredAt: state.lastFiredAt ?? null,
  };
}

type FollowWithRelations = Prisma.FollowGetPayload<{
  include: { target: { select: { id: true; nickname: true } }; rules: true };
}>;

function toFollowDto(follow: FollowWithRelations): FollowDto {
  return {
    id: follow.id,
    target: { userId: follow.target.id, nickname: follow.target.nickname },
    createdAt: follow.createdAt.toISOString(),
    rules: follow.rules
      .slice()
      .sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime())
      .map(toRuleDto),
  };
}

const followInclude = {
  target: { select: { id: true, nickname: true } },
  rules: true,
} as const;

export async function listFollows(
  prisma: PrismaClient,
  userId: string,
): Promise<FollowDto[]> {
  const follows = await prisma.follow.findMany({
    where: { followerId: userId },
    include: followInclude,
    orderBy: { createdAt: 'asc' },
  });
  return follows.map(toFollowDto);
}

/**
 * Idempotent: following the same member twice returns the existing follow
 * instead of creating a duplicate or failing.
 */
export async function createFollow(
  prisma: PrismaClient,
  userId: string,
  targetUserId: string,
  rules: Array<{ type: RuleType; config?: unknown }> = [],
): Promise<{ follow: FollowDto; created: boolean }> {
  if (targetUserId === userId) {
    throw validationError('You cannot follow yourself');
  }

  const membership = await requireMembership(prisma, userId);
  const target = await prisma.familyMember.findFirst({
    where: { familyId: membership.familyId, userId: targetUserId },
    select: { userId: true },
  });
  // Same 404 for "not in this family" and "no such user": no enumeration.
  if (!target) throw notFound('Family member not found');

  const existing = await prisma.follow.findUnique({
    where: { followerId_targetId: { followerId: userId, targetId: targetUserId } },
    include: followInclude,
  });
  if (existing) return { follow: toFollowDto(existing), created: false };

  const seen = new Set<string>();
  const prepared = rules.map((rule) => {
    if (seen.has(rule.type)) {
      throw validationError(`Duplicate rule type: ${rule.type}`);
    }
    seen.add(rule.type);
    return {
      type: rule.type,
      config: JSON.stringify(parseRuleConfig(rule.type, rule.config)),
      state: JSON.stringify(defaultState(rule.type)),
    };
  });

  try {
    const follow = await prisma.follow.create({
      data: {
        familyId: membership.familyId,
        followerId: userId,
        targetId: targetUserId,
        rules: { create: prepared },
      },
      include: followInclude,
    });
    return { follow: toFollowDto(follow), created: true };
  } catch (error) {
    // Lost a race against a concurrent create; fall back to the existing row.
    if (isUniqueConstraintError(error)) {
      const raced = await prisma.follow.findUnique({
        where: { followerId_targetId: { followerId: userId, targetId: targetUserId } },
        include: followInclude,
      });
      if (raced) return { follow: toFollowDto(raced), created: false };
    }
    throw error;
  }
}

export async function deleteFollow(
  prisma: PrismaClient,
  userId: string,
  followId: string,
): Promise<void> {
  const result = await prisma.follow.deleteMany({
    where: { id: followId, followerId: userId },
  });
  if (result.count === 0) throw notFound('Follow not found');
}

export async function listFollowers(
  prisma: PrismaClient,
  userId: string,
): Promise<FollowerDto[]> {
  const follows = await prisma.follow.findMany({
    where: { targetId: userId },
    include: { follower: { select: { id: true, nickname: true } } },
    orderBy: { createdAt: 'desc' },
  });
  return follows.map((follow) => ({
    userId: follow.follower.id,
    nickname: follow.follower.nickname,
    since: follow.createdAt.toISOString(),
  }));
}

async function requireOwnedFollow(
  prisma: PrismaClient,
  userId: string,
  followId: string,
): Promise<{ id: string; targetId: string; rules: AlertRule[] }> {
  const follow = await prisma.follow.findFirst({
    where: { id: followId, followerId: userId },
    include: { rules: true },
  });
  if (!follow) throw notFound('Follow not found');
  return follow;
}

export async function createRule(
  prisma: PrismaClient,
  userId: string,
  followId: string,
  type: RuleType,
  configInput: unknown,
): Promise<AlertRuleDto> {
  const follow = await requireOwnedFollow(prisma, userId, followId);
  if (follow.rules.length >= MAX_RULES_PER_FOLLOW) {
    throw validationError(`A follow can have at most ${MAX_RULES_PER_FOLLOW} rules`);
  }
  if (follow.rules.some((rule) => rule.type === type)) {
    throw conflict('A rule of this type already exists');
  }

  const rule = await prisma.alertRule.create({
    data: {
      followId: follow.id,
      type,
      config: JSON.stringify(parseRuleConfig(type, configInput)),
      state: JSON.stringify(defaultState(type)),
    },
  });
  return toRuleDto(rule);
}

export async function updateRule(
  prisma: PrismaClient,
  userId: string,
  followId: string,
  ruleId: string,
  patch: { enabled?: boolean; config?: unknown },
): Promise<AlertRuleDto> {
  await requireOwnedFollow(prisma, userId, followId);
  const rule = await prisma.alertRule.findFirst({
    where: { id: ruleId, followId },
  });
  if (!rule) throw notFound('Alert rule not found');

  const type = rule.type as RuleType;
  let config = rule.config;
  if (patch.config !== undefined) {
    // Merge so a partial config keeps unspecified thresholds.
    const current = JSON.parse(rule.config) as Record<string, unknown>;
    const incoming =
      patch.config && typeof patch.config === 'object'
        ? (patch.config as Record<string, unknown>)
        : {};
    config = JSON.stringify(parseRuleConfig(type, { ...current, ...incoming }));
  }

  const updated = await prisma.alertRule.update({
    where: { id: rule.id },
    data: {
      ...(patch.enabled !== undefined ? { enabled: patch.enabled } : {}),
      ...(patch.config !== undefined ? { config } : {}),
    },
  });
  return toRuleDto(updated);
}

export async function deleteRule(
  prisma: PrismaClient,
  userId: string,
  followId: string,
  ruleId: string,
): Promise<void> {
  await requireOwnedFollow(prisma, userId, followId);
  const result = await prisma.alertRule.deleteMany({
    where: { id: ruleId, followId },
  });
  if (result.count === 0) throw notFound('Alert rule not found');
}

/** Removes every follow touching a user (member removed / left the family). */
export async function cleanupFollowsForUser(
  tx: Prisma.TransactionClient,
  userId: string,
): Promise<void> {
  await tx.follow.deleteMany({
    where: { OR: [{ followerId: userId }, { targetId: userId }] },
  });
}
