import { z } from 'zod';
import { validationError } from './errors.ts';

/** Validates `input` with a zod schema and throws a uniform VALIDATION error. */
export function parseInput<T extends z.ZodTypeAny>(schema: T, input: unknown): z.infer<T> {
  const result = schema.safeParse(input);
  if (result.success) return result.data;

  const message = result.error.issues
    .map((issue) => {
      const path = issue.path.length > 0 ? issue.path.join('.') : 'body';
      return `${path}: ${issue.message}`;
    })
    .join('; ');

  throw validationError(message);
}

/** Accepts any string Date.parse can interpret (full ISO timestamps and ISO dates). */
export const isoDateString = z.string().min(1).refine(
  (value) => !Number.isNaN(Date.parse(value)),
  { message: 'Invalid ISO 8601 date' },
);

export const idParam = z.object({ id: z.string().min(1).max(64) });
export const userIdParam = z.object({ userId: z.string().min(1).max(64) });

export const pointSchema = z.object({
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
  accuracy: z.number().min(0).max(100_000),
  speed: z.number().min(0).max(10_000).optional(),
  bearing: z.number().min(0).max(360).optional(),
  battery: z.number().int().min(0).max(100).optional(),
  provider: z.string().min(1).max(32).optional(),
  recordedAt: z.string().datetime({ offset: true }),
});

export const ingestSchema = z.object({
  commandId: z.string().min(1).max(64).optional(),
  points: z.array(pointSchema).min(1).max(100),
});

export const historyQuerySchema = z.object({
  from: isoDateString.optional(),
  to: isoDateString.optional(),
  limit: z.coerce.number().int().min(1).max(5000).default(500),
});

export function parseDate(value: string): Date {
  return new Date(value);
}

/* ------------------------------------------------------------------ */
/* Follow / alert rules                                                */
/* ------------------------------------------------------------------ */

export const RULE_TYPES = ['MOVEMENT', 'STALE', 'LOW_BATTERY', 'GEOFENCE'] as const;
export type RuleType = (typeof RULE_TYPES)[number];

export const ruleTypeSchema = z.enum(RULE_TYPES);

export const movementConfigSchema = z
  .object({
    thresholdMeters: z.number().int().min(200).max(50_000).default(500),
    cooldownMinutes: z.number().int().min(5).max(1440).default(30),
  })
  .strict();

export const staleConfigSchema = z
  .object({
    hours: z.number().int().min(1).max(168).default(12),
  })
  .strict();

export const batteryConfigSchema = z
  .object({
    percent: z.number().int().min(1).max(50).default(20),
  })
  .strict();

const clockSchema = z
  .string()
  .regex(/^([01]\d|2[0-3]):[0-5]\d$/, 'Time must be HH:mm');

export const geofenceWindowSchema = z
  .object({
    /** 1 = Monday ... 7 = Sunday. Empty means every day. */
    days: z
      .array(z.number().int().min(1).max(7))
      .max(7)
      .default([])
      .refine((days) => new Set(days).size === days.length, {
        message: 'days must be unique',
      }),
    start: clockSchema,
    end: clockSchema,
  })
  .strict()
  .refine((window) => window.start !== window.end, {
    message: 'start and end must differ',
  });

export const geofenceConfigSchema = z
  .object({
    placeId: z.string().min(1).max(64),
    event: z.enum(['ENTER', 'LEAVE', 'BOTH']).default('BOTH'),
    window: geofenceWindowSchema.nullable().optional().default(null),
  })
  .strict();

export type MovementConfig = z.infer<typeof movementConfigSchema>;
export type StaleConfig = z.infer<typeof staleConfigSchema>;
export type BatteryConfig = z.infer<typeof batteryConfigSchema>;
export type GeofenceWindow = z.infer<typeof geofenceWindowSchema>;
export type GeofenceConfig = z.infer<typeof geofenceConfigSchema>;
export type RuleConfig =
  | MovementConfig
  | StaleConfig
  | BatteryConfig
  | GeofenceConfig;

/** Parses a type-specific config, applying defaults for omitted fields. */
export function parseRuleConfig(type: RuleType, input: unknown): RuleConfig {
  switch (type) {
    case 'MOVEMENT':
      return parseInput(movementConfigSchema, input ?? {});
    case 'STALE':
      return parseInput(staleConfigSchema, input ?? {});
    case 'LOW_BATTERY':
      return parseInput(batteryConfigSchema, input ?? {});
    case 'GEOFENCE':
      return parseInput(geofenceConfigSchema, input ?? {});
  }
}

/* ------------------------------------------------------------------ */
/* Places                                                              */
/* ------------------------------------------------------------------ */

export const PLACE_TYPES = ['home', 'school', 'company', 'other'] as const;

export const placeTypeSchema = z.enum(PLACE_TYPES);

export const createPlaceSchema = z
  .object({
    name: z.string().trim().min(1).max(20),
    type: placeTypeSchema.default('other'),
    lat: z.number().min(-90).max(90),
    lng: z.number().min(-180).max(180),
    radiusMeters: z.number().int().min(50).max(5000).default(200),
  })
  .strict();

export const updatePlaceSchema = z
  .object({
    name: z.string().trim().min(1).max(20).optional(),
    type: placeTypeSchema.optional(),
    lat: z.number().min(-90).max(90).optional(),
    lng: z.number().min(-180).max(180).optional(),
    radiusMeters: z.number().int().min(50).max(5000).optional(),
  })
  .strict();

export const createFollowSchema = z.object({
  targetUserId: z.string().min(1).max(64),
  rules: z
    .array(
      z.object({
        type: ruleTypeSchema,
        config: z.unknown().optional(),
      }),
    )
    .max(10)
    .optional(),
});

export const createRuleSchema = z.object({
  type: ruleTypeSchema,
  config: z.unknown().optional(),
});

export const updateRuleSchema = z.object({
  enabled: z.boolean().optional(),
  config: z.unknown().optional(),
});

export const alertsQuerySchema = z.object({
  limit: z.coerce.number().int().min(1).max(100).default(50),
  unread: z.enum(['1', '0']).optional(),
  after: isoDateString.optional(),
  before: isoDateString.optional(),
});

export const followIdParams = z.object({
  id: z.string().min(1).max(64),
});

export const ruleParams = z.object({
  id: z.string().min(1).max(64),
  ruleId: z.string().min(1).max(64),
});

/** Start (UTC) of the current day, used as the default history `from` value. */
export function startOfTodayUtc(now = new Date()): Date {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
}
