import type { AlertRule, Prisma, PrismaClient } from '@prisma/client';
import { conflict, notFound, validationError } from '../lib/errors.ts';
import { isUniqueConstraintError } from '../lib/prisma.ts';
import {
  parseRuleConfig,
  type GeofenceConfig,
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
  /** Present for GEOFENCE rules so clients can render the place name. */
  place: { id: string; name: string } | null;
  lastFiredAt: string | null;
}

type PlaceLookup = Map<string, { id: string; name: string }>;

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

const MAX_RULES_PER_FOLLOW = 10;

/** Evaluation state is opaque to clients; only `lastFiredAt` is surfaced. */
interface RuleStateLike {
  lastFiredAt?: string | null;
  armed?: boolean;
  anchorLat?: number | null;
  anchorLng?: number | null;
  lastRecordedAtMs?: number | null;
  inside?: boolean | null;
}

export function parseState(raw: string): RuleStateLike {
  try {
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? (parsed as RuleStateLike) : {};
  } catch {
    return {};
  }
}

export function defaultState(
  type: RuleType,
  nowMs: number = Date.now(),
): Record<string, unknown> {
  switch (type) {
    case 'MOVEMENT':
      return { anchorLat: null, anchorLng: null, lastFiredAt: null, lastRecordedAtMs: null };
    case 'STALE':
      // Armed only once a fresh location arrives: following somebody who has
      // already been offline for days must not fire immediately.
      return { armed: false, lastFiredAt: null };
    case 'LOW_BATTERY':
      return { armed: true, lastFiredAt: null };
    case 'GEOFENCE':
      // epochMs gates backlog: points recorded before the rule existed (or
      // before a reset) must never fire or seed the baseline.
      return {
        inside: null,
        lastFiredAt: null,
        lastRecordedAtMs: null,
        epochMs: nowMs,
      };
  }
}

function safeGeofenceConfig(): GeofenceConfig {
  return { placeId: '', event: 'BOTH', window: null };
}

function toRuleDto(rule: AlertRule, places: PlaceLookup = new Map()): AlertRuleDto {
  const state = parseState(rule.state);
  const type = rule.type as RuleType;
  let config: RuleConfig;
  try {
    config = parseRuleConfig(type, JSON.parse(rule.config));
  } catch {
    // A corrupted config must not take down the whole follow list.
    config = type === 'GEOFENCE' ? safeGeofenceConfig() : parseRuleConfig(type, {});
  }
  const placeId =
    type === 'GEOFENCE' ? (config as GeofenceConfig).placeId : null;
  return {
    id: rule.id,
    type,
    enabled: rule.enabled,
    config,
    place: placeId ? places.get(placeId) ?? null : null,
    lastFiredAt: state.lastFiredAt ?? null,
  };
}

type FollowWithRelations = Prisma.FollowGetPayload<{
  include: { target: { select: { id: true; nickname: true } }; rules: true };
}>;

function toFollowDto(follow: FollowWithRelations, places: PlaceLookup): FollowDto {
  return {
    id: follow.id,
    target: { userId: follow.target.id, nickname: follow.target.nickname },
    createdAt: follow.createdAt.toISOString(),
    rules: follow.rules
      .slice()
      .sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime())
      .map((rule) => toRuleDto(rule, places)),
  };
}

const followInclude = {
  target: { select: { id: true, nickname: true } },
  rules: true,
} as const;

async function loadFamilyPlaces(
  prisma: PrismaClient,
  familyId: string,
): Promise<PlaceLookup> {
  const places = await prisma.place.findMany({
    where: { familyId },
    select: { id: true, name: true },
  });
  return new Map(places.map((place) => [place.id, place]));
}

/**
 * Loads the referenced places and rejects any id outside the family. Used for
 * GEOFENCE rules; the returned map feeds the rule DTO.
 */
async function loadGeofencePlaces(
  prisma: PrismaClient,
  familyId: string,
  configs: GeofenceConfig[],
): Promise<PlaceLookup> {
  const ids = Array.from(new Set(configs.map((config) => config.placeId)));
  if (ids.length === 0) return new Map();
  const places = await prisma.place.findMany({
    where: { familyId, id: { in: ids } },
    select: { id: true, name: true },
  });
  if (places.length !== ids.length) throw notFound('Place not found');
  return new Map(places.map((place) => [place.id, place]));
}

export async function listFollows(
  prisma: PrismaClient,
  userId: string,
): Promise<FollowDto[]> {
  const membership = await requireMembership(prisma, userId);
  const [follows, places] = await Promise.all([
    prisma.follow.findMany({
      where: { followerId: userId },
      include: followInclude,
      orderBy: { createdAt: 'asc' },
    }),
    loadFamilyPlaces(prisma, membership.familyId),
  ]);
  return follows.map((follow) => toFollowDto(follow, places));
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

  const places = await loadFamilyPlaces(prisma, membership.familyId);
  const existing = await prisma.follow.findUnique({
    where: { followerId_targetId: { followerId: userId, targetId: targetUserId } },
    include: followInclude,
  });
  if (existing) return { follow: toFollowDto(existing, places), created: false };

  const seen = new Set<string>();
  const prepared = rules.map((rule) => {
    // Geofence rules are repeatable (home + school); the other types are not.
    if (rule.type !== 'GEOFENCE' && seen.has(rule.type)) {
      throw validationError(`Duplicate rule type: ${rule.type}`);
    }
    seen.add(rule.type);
    return {
      type: rule.type,
      config: parseRuleConfig(rule.type, rule.config),
    };
  });

  const geofencePlaceIds = new Set<string>();
  for (const item of prepared) {
    if (item.type !== 'GEOFENCE') continue;
    const placeId = (item.config as GeofenceConfig).placeId;
    if (!places.has(placeId)) throw notFound('Place not found');
    if (geofencePlaceIds.has(placeId)) {
      throw conflict('A geofence rule for this place already exists');
    }
    geofencePlaceIds.add(placeId);
  }

  const ruleRows = prepared.map((item) => ({
    type: item.type,
    config: JSON.stringify(item.config),
    state: JSON.stringify(defaultState(item.type)),
  }));

  try {
    const follow = await prisma.follow.create({
      data: {
        familyId: membership.familyId,
        followerId: userId,
        targetId: targetUserId,
        rules: { create: ruleRows },
      },
      include: followInclude,
    });
    return { follow: toFollowDto(follow, places), created: true };
  } catch (error) {
    // Lost a race against a concurrent create; fall back to the existing row.
    if (isUniqueConstraintError(error)) {
      const raced = await prisma.follow.findUnique({
        where: { followerId_targetId: { followerId: userId, targetId: targetUserId } },
        include: followInclude,
      });
      if (raced) return { follow: toFollowDto(raced, places), created: false };
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
): Promise<{ id: string; familyId: string; targetId: string; rules: AlertRule[] }> {
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
  if (type !== 'GEOFENCE' && follow.rules.some((rule) => rule.type === type)) {
    throw conflict('A rule of this type already exists');
  }

  const config = parseRuleConfig(type, configInput);
  let places: PlaceLookup = new Map();
  if (type === 'GEOFENCE') {
    const placeId = (config as GeofenceConfig).placeId;
    const duplicate = follow.rules.some((existing) => {
      if (existing.type !== 'GEOFENCE') return false;
      try {
        return (
          (JSON.parse(existing.config) as GeofenceConfig).placeId === placeId
        );
      } catch {
        return false;
      }
    });
    if (duplicate) throw conflict('A geofence rule for this place already exists');
    places = await loadGeofencePlaces(prisma, follow.familyId, [
      config as GeofenceConfig,
    ]);
  }

  const rule = await prisma.alertRule.create({
    data: {
      followId: follow.id,
      type,
      config: JSON.stringify(config),
      state: JSON.stringify(defaultState(type)),
    },
  });
  return toRuleDto(rule, places);
}

export async function updateRule(
  prisma: PrismaClient,
  userId: string,
  followId: string,
  ruleId: string,
  patch: { enabled?: boolean; config?: unknown },
): Promise<AlertRuleDto> {
  const follow = await requireOwnedFollow(prisma, userId, followId);
  const rule = await prisma.alertRule.findFirst({
    where: { id: ruleId, followId },
  });
  if (!rule) throw notFound('Alert rule not found');

  const type = rule.type as RuleType;
  let config = rule.config;
  let parsed: RuleConfig | null = null;
  if (patch.config !== undefined) {
    // Merge so a partial config keeps unspecified thresholds. Clients turning
    // a geofence window off must send `window: null` explicitly.
    const current = JSON.parse(rule.config) as Record<string, unknown>;
    const incoming =
      patch.config && typeof patch.config === 'object'
        ? (patch.config as Record<string, unknown>)
        : {};
    parsed = parseRuleConfig(type, { ...current, ...incoming });
    config = JSON.stringify(parsed);
  }

  let places: PlaceLookup = new Map();
  if (type === 'GEOFENCE') {
    const effective =
      parsed ?? (parseRuleConfig(type, JSON.parse(config)) as GeofenceConfig);
    places = await loadGeofencePlaces(prisma, follow.familyId, [
      effective as GeofenceConfig,
    ]);
  }

  // Re-enabling or reconfiguring a geofence rule invalidates the cached
  // inside/outside state: the next point must establish a fresh baseline.
  const resetInside =
    type === 'GEOFENCE' &&
    (patch.config !== undefined || (patch.enabled === true && !rule.enabled));

  const updated = await prisma.alertRule.update({
    where: { id: rule.id },
    data: {
      ...(patch.enabled !== undefined ? { enabled: patch.enabled } : {}),
      ...(patch.config !== undefined ? { config } : {}),
      ...(resetInside
        ? {
            state: JSON.stringify({
              ...parseState(rule.state),
              inside: null,
              epochMs: Date.now(),
            }),
          }
        : {}),
    },
  });
  return toRuleDto(updated, places);
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

/**
 * Drops the cached inside/outside state of every geofence rule that points at
 * this place. Called when the place's coordinates or radius change, so the
 * next location establishes a fresh baseline instead of reporting a spurious
 * enter/leave caused by moving the fence.
 */
export async function resetGeofenceStateForPlace(
  tx: Prisma.TransactionClient,
  familyId: string,
  placeId: string,
): Promise<number> {
  const rules = await tx.alertRule.findMany({
    where: { type: 'GEOFENCE', follow: { familyId } },
    select: { id: true, config: true, state: true },
  });

  const affected = rules.filter((rule) => {
    try {
      return (JSON.parse(rule.config) as GeofenceConfig).placeId === placeId;
    } catch {
      return false;
    }
  });

  for (const rule of affected) {
    await tx.alertRule.update({
      where: { id: rule.id },
      data: {
        state: JSON.stringify({
          ...parseState(rule.state),
          inside: null,
          epochMs: Date.now(),
        }),
      },
    });
  }
  return affected.length;
}
