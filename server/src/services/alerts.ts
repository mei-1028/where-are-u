import type { Alert, AlertRule, Prisma, PrismaClient } from '@prisma/client';
import { notFound } from '../lib/errors.ts';
import { isUniqueConstraintError } from '../lib/prisma.ts';
import {
  parseRuleConfig,
  type BatteryConfig,
  type MovementConfig,
  type RuleType,
  type StaleConfig,
} from '../lib/validation.ts';
import { parseState } from './follows.ts';
import type { RealtimeHub } from './realtime.ts';
import type { IngestPoint } from './locations.ts';

/* ------------------------------------------------------------------ */
/* Pure evaluation                                                     */
/* ------------------------------------------------------------------ */

export interface AlertDraft {
  type: RuleType;
  title: string;
  body: string;
  data: Record<string, unknown>;
  silent: boolean;
  occurredAt: Date;
  dedupeKey: string;
}

export interface MovementState {
  anchorLat: number | null;
  anchorLng: number | null;
  lastFiredAt: string | null;
  lastRecordedAtMs: number | null;
}

export interface ArmedState {
  armed: boolean;
  lastFiredAt: string | null;
}

/**
 * Points worse than this are ignored for movement: a 500m cell-tower fix is
 * not evidence that somebody moved 500m.
 */
export const MAX_MOVEMENT_ACCURACY_M = 100;

const CHINA_UTC_OFFSET_HOURS = 8;

/** 23:00–07:00 China time. No DST in China, so a fixed offset is exact. */
export function isQuietHours(now: Date): boolean {
  const hour = (now.getUTCHours() + CHINA_UTC_OFFSET_HOURS) % 24;
  return hour >= 23 || hour < 7;
}

export function haversineMeters(
  lat1: number,
  lng1: number,
  lat2: number,
  lng2: number,
): number {
  const toRad = (value: number) => (value * Math.PI) / 180;
  const earthRadius = 6_371_000;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * earthRadius * Math.asin(Math.min(1, Math.sqrt(a)));
}

function formatDistance(meters: number): string {
  if (meters >= 1000) return `${(meters / 1000).toFixed(1)} 公里`;
  return `${Math.round(meters)} 米`;
}

function normalizeMovementState(raw: unknown): MovementState {
  const state = (raw ?? {}) as Partial<MovementState>;
  return {
    anchorLat: typeof state.anchorLat === 'number' ? state.anchorLat : null,
    anchorLng: typeof state.anchorLng === 'number' ? state.anchorLng : null,
    lastFiredAt: typeof state.lastFiredAt === 'string' ? state.lastFiredAt : null,
    lastRecordedAtMs:
      typeof state.lastRecordedAtMs === 'number' ? state.lastRecordedAtMs : null,
  };
}

function normalizeArmedState(raw: unknown, defaultArmed: boolean): ArmedState {
  const state = (raw ?? {}) as Partial<ArmedState>;
  return {
    armed: typeof state.armed === 'boolean' ? state.armed : defaultArmed,
    lastFiredAt: typeof state.lastFiredAt === 'string' ? state.lastFiredAt : null,
  };
}

export interface EvaluatedPoint {
  lat: number;
  lng: number;
  accuracy: number;
  recordedAt: Date;
}

/**
 * Drifting-baseline movement rule: the anchor moves to the latest position only
 * when an alert actually fires, so the next alert reports the total distance
 * travelled since the previous notification.
 */
export function evaluateMovementPoint(
  config: MovementConfig,
  rawState: unknown,
  ruleId: string,
  nickname: string,
  point: EvaluatedPoint,
  now: Date,
): { state: MovementState; draft: AlertDraft | null } {
  const state = normalizeMovementState(rawState);
  const recordedAtMs = point.recordedAt.getTime();
  if (state.lastRecordedAtMs !== null && recordedAtMs <= state.lastRecordedAtMs) {
    return { state, draft: null };
  }

  // First point after following (or after resuming sharing) is only a baseline.
  if (state.anchorLat === null || state.anchorLng === null) {
    return {
      state: {
        ...state,
        anchorLat: point.lat,
        anchorLng: point.lng,
        lastRecordedAtMs: recordedAtMs,
      },
      draft: null,
    };
  }

  const distance = haversineMeters(
    state.anchorLat,
    state.anchorLng,
    point.lat,
    point.lng,
  );
  const effective = distance - point.accuracy;
  const cooldownMs = config.cooldownMinutes * 60_000;
  const lastFiredMs = state.lastFiredAt ? Date.parse(state.lastFiredAt) : 0;
  const coolingDown = lastFiredMs > 0 && now.getTime() - lastFiredMs < cooldownMs;

  if (effective < config.thresholdMeters || coolingDown) {
    // Keep the anchor: the next alert should report the whole displacement.
    return { state: { ...state, lastRecordedAtMs: recordedAtMs }, draft: null };
  }

  const draft: AlertDraft = {
    type: 'MOVEMENT',
    title: `${nickname} 位置发生变化`,
    body: `已移动约 ${formatDistance(distance)}`,
    data: {
      lat: point.lat,
      lng: point.lng,
      accuracy: point.accuracy,
      distanceMeters: Math.round(distance),
      recordedAt: point.recordedAt.toISOString(),
    },
    silent: false,
    occurredAt: point.recordedAt,
    dedupeKey: `mv:${ruleId}:${point.recordedAt.toISOString()}`,
  };

  return {
    state: {
      anchorLat: point.lat,
      anchorLng: point.lng,
      lastFiredAt: now.toISOString(),
      lastRecordedAtMs: recordedAtMs,
    },
    draft,
  };
}

export function evaluateBatteryPoint(
  config: BatteryConfig,
  rawState: unknown,
  ruleId: string,
  nickname: string,
  point: EvaluatedPoint & { battery: number },
  now: Date,
): { state: ArmedState & { lastRecordedAtMs: number | null }; draft: AlertDraft | null } {
  const base = normalizeArmedState(rawState, true);
  const rawCursor = (rawState as { lastRecordedAtMs?: unknown } | null)?.lastRecordedAtMs;
  const cursor = typeof rawCursor === 'number' ? rawCursor : null;
  const recordedAtMs = point.recordedAt.getTime();
  if (cursor !== null && recordedAtMs <= cursor) {
    return { state: { ...base, lastRecordedAtMs: cursor }, draft: null };
  }

  let armed = base.armed;
  let lastFiredAt = base.lastFiredAt;
  let draft: AlertDraft | null = null;

  if (point.battery <= config.percent && armed) {
    armed = false;
    lastFiredAt = now.toISOString();
    draft = {
      type: 'LOW_BATTERY',
      title: `${nickname} 手机电量低`,
      body: `剩余 ${point.battery}%，可能很快关机`,
      data: {
        lat: point.lat,
        lng: point.lng,
        battery: point.battery,
        recordedAt: point.recordedAt.toISOString(),
      },
      // A dead battery at 2am can wait until morning; it stays in the inbox.
      silent: isQuietHours(now),
      occurredAt: point.recordedAt,
      dedupeKey: `bt:${ruleId}:${point.recordedAt.toISOString()}`,
    };
  } else if (point.battery >= config.percent + 5 && !armed) {
    // Hysteresis: charging back above threshold re-arms the rule.
    armed = true;
  }

  return {
    state: { armed, lastFiredAt, lastRecordedAtMs: recordedAtMs },
    draft,
  };
}

export interface StaleLocation {
  id: string;
  lat: number;
  lng: number;
  recordedAt: Date;
}

export function evaluateStaleRule(
  config: StaleConfig,
  rawState: unknown,
  ruleId: string,
  nickname: string,
  lastLocation: StaleLocation,
  now: Date,
): { state: ArmedState; draft: AlertDraft | null } {
  const state = normalizeArmedState(rawState, false);
  const overdueMs = config.hours * 3_600_000;
  const overdue = now.getTime() - lastLocation.recordedAt.getTime() > overdueMs;

  if (overdue && state.armed) {
    return {
      state: { armed: false, lastFiredAt: now.toISOString() },
      draft: {
        type: 'STALE',
        title: `${nickname} 已 ${config.hours} 小时没有更新位置`,
        body: '可能关机、断网，或 App 被系统清理',
        data: {
          lastRecordedAt: lastLocation.recordedAt.toISOString(),
          lat: lastLocation.lat,
          lng: lastLocation.lng,
          hours: config.hours,
        },
        silent: false,
        occurredAt: now,
        dedupeKey: `st:${ruleId}:${lastLocation.id}`,
      },
    };
  }

  if (!overdue && !state.armed) {
    return { state: { ...state, armed: true }, draft: null };
  }
  return { state, draft: null };
}

/* ------------------------------------------------------------------ */
/* Persistence + delivery                                              */
/* ------------------------------------------------------------------ */

export interface AlertDto {
  id: string;
  type: string;
  target: { userId: string; nickname: string };
  title: string;
  body: string;
  silent: boolean;
  data: Record<string, unknown>;
  occurredAt: string;
  createdAt: string;
  readAt: string | null;
}

export function toAlertDto(alert: Alert, targetNickname: string): AlertDto {
  let data: Record<string, unknown> = {};
  try {
    const parsed: unknown = JSON.parse(alert.data);
    if (parsed && typeof parsed === 'object') data = parsed as Record<string, unknown>;
  } catch {
    // Corrupted payload: keep the alert readable with empty data.
  }
  return {
    id: alert.id,
    type: alert.type,
    target: { userId: alert.targetId, nickname: targetNickname },
    title: alert.title,
    body: alert.body,
    silent: alert.silent,
    data,
    occurredAt: alert.occurredAt.toISOString(),
    createdAt: alert.createdAt.toISOString(),
    readAt: alert.readAt ? alert.readAt.toISOString() : null,
  };
}

interface FireInput {
  userId: string;
  familyId: string;
  targetId: string;
  targetNickname: string;
  ruleId: string;
  draft: AlertDraft;
}

/**
 * Inserts the alert and pushes it to every connected device of the follower.
 * The unique dedupeKey makes a duplicate insert a no-op, which keeps retries
 * and server restarts from double-notifying.
 */
async function fireAlert(
  prisma: PrismaClient,
  realtime: RealtimeHub,
  input: FireInput,
): Promise<boolean> {
  let alert: Alert;
  try {
    alert = await prisma.alert.create({
      data: {
        userId: input.userId,
        familyId: input.familyId,
        ruleId: input.ruleId,
        targetId: input.targetId,
        type: input.draft.type,
        title: input.draft.title,
        body: input.draft.body,
        data: JSON.stringify(input.draft.data),
        silent: input.draft.silent,
        dedupeKey: input.draft.dedupeKey,
        occurredAt: input.draft.occurredAt,
      },
    });
  } catch (error) {
    if (isUniqueConstraintError(error)) return false;
    throw error;
  }

  const devices = await prisma.device.findMany({
    where: { userId: input.userId, active: true },
    select: { id: true },
  });
  const payload = {
    type: 'alert',
    alert: toAlertDto(alert, input.targetNickname),
  };
  for (const device of devices) {
    realtime.send(device.id, payload);
  }
  return true;
}

function readConfig(rule: AlertRule): unknown {
  return JSON.parse(rule.config) as unknown;
}

type FollowWithRules = {
  id: string;
  familyId: string;
  followerId: string;
  targetId: string;
  target: { id: string; nickname: string };
  rules: AlertRule[];
};

/** The subset the sweep needs; it does not load the rule list. */
type SweepFollow = Omit<FollowWithRules, 'rules'>;

/**
 * Runs after a location batch has been committed. Never throws into the ingest
 * path: alerting is best-effort and must not fail a location write.
 */
export async function evaluateIngest(
  prisma: PrismaClient,
  realtime: RealtimeHub,
  targetUserId: string,
  points: IngestPoint[],
  now = new Date(),
): Promise<void> {
  if (points.length === 0) return;

  const membership = await prisma.familyMember.findUnique({
    where: { userId: targetUserId },
    select: { sharingPaused: true },
  });
  if (!membership || membership.sharingPaused) return;

  const follows = (await prisma.follow.findMany({
    where: { targetId: targetUserId },
    include: {
      target: { select: { id: true, nickname: true } },
      rules: { where: { enabled: true } },
    },
  })) as FollowWithRules[];
  if (follows.length === 0) return;

  const ordered = points
    .slice()
    .sort((a, b) => Date.parse(a.recordedAt) - Date.parse(b.recordedAt))
    .map((point) => ({
      lat: point.lat,
      lng: point.lng,
      accuracy: Math.round(point.accuracy),
      recordedAt: new Date(point.recordedAt),
      battery: point.battery,
    }));

  let firstError: unknown = null;
  for (const follow of follows) {
    for (const rule of follow.rules) {
      try {
        await evaluateRule(prisma, realtime, follow, rule, ordered, now);
      } catch (error) {
        // One broken rule must not block the others; rethrow afterwards so the
        // caller can log it without failing the location write.
        firstError ??= error;
      }
    }
  }
  if (firstError) throw firstError;
}

async function evaluateRule(
  prisma: PrismaClient,
  realtime: RealtimeHub,
  follow: FollowWithRules,
  rule: AlertRule,
  points: Array<EvaluatedPoint & { battery?: number }>,
  now: Date,
): Promise<void> {
  const type = rule.type as RuleType;
  const parsedState = parseState(rule.state);

  if (type === 'MOVEMENT') {
    const config = parseRuleConfig(type, readConfig(rule)) as MovementConfig;
    let state: unknown = parsedState;
    let draft: AlertDraft | null = null;
    for (const point of points) {
      if (point.accuracy > MAX_MOVEMENT_ACCURACY_M) continue;
      const result = evaluateMovementPoint(
        config,
        state,
        rule.id,
        follow.target.nickname,
        point,
        now,
      );
      state = result.state;
      draft = result.draft ?? draft;
      // Once one alert fires the cooldown suppresses the rest of the batch.
      if (result.draft) break;
    }
    if (draft) {
      await fireAlert(prisma, realtime, {
        userId: follow.followerId,
        familyId: follow.familyId,
        targetId: follow.targetId,
        targetNickname: follow.target.nickname,
        ruleId: rule.id,
        draft,
      });
    }
    await prisma.alertRule.update({
      where: { id: rule.id },
      data: { state: JSON.stringify(state) },
    });
    return;
  }

  if (type === 'LOW_BATTERY') {
    const config = parseRuleConfig(type, readConfig(rule)) as BatteryConfig;
    let latest: (EvaluatedPoint & { battery: number }) | null = null;
    for (const point of points) {
      if (typeof point.battery === 'number') latest = { ...point, battery: point.battery };
    }
    if (!latest) return;

    const result = evaluateBatteryPoint(
      config,
      parsedState,
      rule.id,
      follow.target.nickname,
      latest,
      now,
    );
    if (result.draft) {
      await fireAlert(prisma, realtime, {
        userId: follow.followerId,
        familyId: follow.familyId,
        targetId: follow.targetId,
        targetNickname: follow.target.nickname,
        ruleId: rule.id,
        draft: result.draft,
      });
    }
    await prisma.alertRule.update({
      where: { id: rule.id },
      data: { state: JSON.stringify(result.state) },
    });
    return;
  }

  // STALE: a fresh location re-arms the rule; the sweep does the firing.
  if (type === 'STALE' && parsedState.armed !== true) {
    await prisma.alertRule.update({
      where: { id: rule.id },
      data: { state: JSON.stringify({ ...parsedState, armed: true }) },
    });
  }
}

/**
 * Periodic pass: fires STALE rules and purges old alerts. Runs under the
 * alert mutex so it cannot race an ingest evaluation.
 */
export async function sweepAlerts(
  prisma: PrismaClient,
  realtime: RealtimeHub,
  now = new Date(),
): Promise<void> {
  const rules = (await prisma.alertRule.findMany({
    where: { type: 'STALE', enabled: true },
    include: {
      follow: {
        include: { target: { select: { id: true, nickname: true } } },
      },
    },
  })) as Array<AlertRule & { follow: SweepFollow }>;

  if (rules.length > 0) {
    const targetIds = Array.from(new Set(rules.map((rule) => rule.follow.targetId)));
    const members = await prisma.familyMember.findMany({
      where: { userId: { in: targetIds } },
      select: { userId: true, sharingPaused: true },
    });
    const paused = new Set(
      members.filter((member) => member.sharingPaused).map((member) => member.userId),
    );

    const lastLocations = new Map<string, StaleLocation>();
    await Promise.all(
      targetIds.map(async (targetId) => {
        const location = await prisma.location.findFirst({
          where: { userId: targetId },
          orderBy: { recordedAt: 'desc' },
          select: { id: true, lat: true, lng: true, recordedAt: true },
        });
        if (location) lastLocations.set(targetId, location);
      }),
    );

    for (const rule of rules) {
      const follow = rule.follow;
      if (paused.has(follow.targetId)) continue;
      const lastLocation = lastLocations.get(follow.targetId);
      if (!lastLocation) continue;

      try {
        const config = parseRuleConfig('STALE', readConfig(rule)) as StaleConfig;
        const result = evaluateStaleRule(
          config,
          parseState(rule.state),
          rule.id,
          follow.target.nickname,
          lastLocation,
          now,
        );
        if (result.draft) {
          await fireAlert(prisma, realtime, {
            userId: follow.followerId,
            familyId: follow.familyId,
            targetId: follow.targetId,
            targetNickname: follow.target.nickname,
            ruleId: rule.id,
            draft: result.draft,
          });
        }
        if (JSON.stringify(result.state) !== JSON.stringify(parseState(rule.state))) {
          await prisma.alertRule.update({
            where: { id: rule.id },
            data: { state: JSON.stringify(result.state) },
          });
        }
      } catch {
        // Keep sweeping the remaining rules.
      }
    }
  }

  await purgeOldAlerts(prisma, now);
}

export interface AlertListOptions {
  limit: number;
  unreadOnly?: boolean;
  after?: Date;
  before?: Date;
}

export async function listAlerts(
  prisma: PrismaClient,
  userId: string,
  options: AlertListOptions,
): Promise<{ alerts: AlertDto[]; unreadCount: number }> {
  const where: Prisma.AlertWhereInput = { userId };
  if (options.unreadOnly) where.readAt = null;

  const createdAt: { gt?: Date; lt?: Date } = {};
  if (options.after) createdAt.gt = options.after;
  if (options.before) createdAt.lt = options.before;
  if (Object.keys(createdAt).length > 0) where.createdAt = createdAt;

  const [rows, unreadCount] = await Promise.all([
    prisma.alert.findMany({
      where,
      orderBy: { createdAt: 'desc' },
      take: options.limit,
      include: { target: { select: { nickname: true } } },
    }),
    prisma.alert.count({ where: { userId, readAt: null } }),
  ]);

  return {
    alerts: rows.map((row) => toAlertDto(row, row.target.nickname)),
    unreadCount,
  };
}

export async function markAlertRead(
  prisma: PrismaClient,
  userId: string,
  alertId: string,
): Promise<void> {
  const result = await prisma.alert.updateMany({
    where: { id: alertId, userId },
    data: { readAt: new Date() },
  });
  if (result.count === 0) throw notFound('Alert not found');
}

export async function markAllAlertsRead(
  prisma: PrismaClient,
  userId: string,
): Promise<number> {
  const result = await prisma.alert.updateMany({
    where: { userId, readAt: null },
    data: { readAt: new Date() },
  });
  return result.count;
}

let lastPurgeAt = 0;

/** Alerts are inbox history, not an archive: keep 90 days. */
export async function purgeOldAlerts(
  prisma: PrismaClient,
  now = new Date(),
  retentionDays = 90,
): Promise<number> {
  if (now.getTime() - lastPurgeAt < 6 * 3_600_000) return 0;
  lastPurgeAt = now.getTime();
  const cutoff = new Date(now.getTime() - retentionDays * 86_400_000);
  const result = await prisma.alert.deleteMany({
    where: { createdAt: { lt: cutoff } },
  });
  return result.count;
}

/**
 * Called when a member resumes sharing: drop the movement anchors so the gap
 * while paused is not reported as one huge movement.
 */
export async function resetMovementAnchors(
  prisma: PrismaClient,
  targetUserId: string,
): Promise<void> {
  const rules = await prisma.alertRule.findMany({
    where: { type: 'MOVEMENT', follow: { targetId: targetUserId } },
    select: { id: true, state: true },
  });
  for (const rule of rules) {
    const state = parseState(rule.state);
    await prisma.alertRule.update({
      where: { id: rule.id },
      data: {
        state: JSON.stringify({
          ...state,
          anchorLat: null,
          anchorLng: null,
          lastRecordedAtMs: null,
        }),
      },
    });
  }
}
