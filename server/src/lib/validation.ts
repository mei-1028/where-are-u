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

/** Start (UTC) of the current day, used as the default history `from` value. */
export function startOfTodayUtc(now = new Date()): Date {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
}
