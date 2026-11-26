import { z } from 'zod';

const JWT_SECRET_MIN_BYTES = 32;

const KNOWN_PLACEHOLDER_SECRETS = new Set([
  'secret',
  'changeme',
  'change-me',
  'your-secret',
  'jwt-secret',
  'test-secret',
  'dev-secret',
  'development-secret',
  'example-secret',
  'replace-with-a-long-random-secret',
  'change-me-before-production',
]);

/**
 * Rejects the usual example values and trivially weak secrets. Randomness
 * itself cannot be proven here, so length is the enforceable part.
 */
function jwtSecretProblem(value: string): string | null {
  const secret = value.trim();

  if (Buffer.byteLength(secret, 'utf8') < JWT_SECRET_MIN_BYTES) {
    return `JWT_SECRET must be at least ${JWT_SECRET_MIN_BYTES} bytes; generate it with a CSPRNG`;
  }

  if (secret.length === 0) return 'JWT_SECRET is required';

  const normalized = secret.toLowerCase();
  const looksLikePlaceholder =
    KNOWN_PLACEHOLDER_SECRETS.has(normalized) ||
    normalized.startsWith('replace-with') ||
    normalized.startsWith('change-me') ||
    normalized.startsWith('your-') ||
    normalized.startsWith('placeholder') ||
    normalized.startsWith('example-') ||
    normalized.includes('{{') ||
    (normalized.startsWith('<') && normalized.endsWith('>'));

  if (looksLikePlaceholder) {
    return 'JWT_SECRET looks like a placeholder; set a random secret of at least 32 bytes';
  }

  if (new Set(secret).size < 8) {
    return 'JWT_SECRET does not look random; generate at least 32 random bytes';
  }

  return null;
}

const jwtSecretSchema = z.string().superRefine((value, ctx) => {
  const problem = jwtSecretProblem(value);
  if (problem) ctx.addIssue({ code: z.ZodIssueCode.custom, message: problem });
});

const envSchema = z.object({
  PORT: z.coerce.number().int().min(1).max(65535).default(3000),
  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),
  JWT_SECRET: jwtSecretSchema,
  CORS_ORIGIN: z.string().min(1).default('*'),
  LOCATE_TIMEOUT_MS: z.coerce.number().int().min(1000).max(300_000).default(30_000),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
  // boolean | hop count | proxy-addr list such as "loopback,uniquelocal"
  TRUST_PROXY: z.string().default('false'),
});

export type TrustProxySetting = boolean | string;

export interface AppConfig {
  port: number;
  databaseUrl: string;
  jwtSecret: string;
  corsOrigin: string;
  locateTimeoutMs: number;
  logLevel: string;
  trustProxy: TrustProxySetting;
}

export type ConfigOverrides = Partial<AppConfig>;

/**
 * Loads configuration from the environment, with optional programmatic
 * overrides (used by tests). Throws a readable error for invalid values.
 */
export function loadConfig(overrides: ConfigOverrides = {}): AppConfig {
  const parsed = envSchema.parse({
    PORT: overrides.port !== undefined ? String(overrides.port) : process.env.PORT,
    DATABASE_URL: overrides.databaseUrl ?? process.env.DATABASE_URL,
    JWT_SECRET: overrides.jwtSecret ?? process.env.JWT_SECRET,
    CORS_ORIGIN: overrides.corsOrigin ?? process.env.CORS_ORIGIN,
    LOCATE_TIMEOUT_MS:
      overrides.locateTimeoutMs !== undefined
        ? String(overrides.locateTimeoutMs)
        : process.env.LOCATE_TIMEOUT_MS,
    LOG_LEVEL: overrides.logLevel ?? process.env.LOG_LEVEL,
    TRUST_PROXY:
      overrides.trustProxy !== undefined ? String(overrides.trustProxy) : process.env.TRUST_PROXY,
  });

  return {
    port: parsed.PORT,
    databaseUrl: parsed.DATABASE_URL,
    jwtSecret: parsed.JWT_SECRET,
    corsOrigin: parsed.CORS_ORIGIN,
    locateTimeoutMs: parsed.LOCATE_TIMEOUT_MS,
    logLevel: parsed.LOG_LEVEL,
    trustProxy: parseTrustProxy(parsed.TRUST_PROXY),
  };
}

function parseTrustProxy(value: string): TrustProxySetting {
  const normalized = value.trim().toLowerCase();
  if (!normalized || normalized === 'false') return false;
  if (normalized === 'true') return true;
  // Everything else is a proxy-addr list (IPs and/or named ranges).
  return value.trim();
}

/** Parses CORS_ORIGIN into the value accepted by @fastify/cors. */
export function parseCorsOrigin(corsOrigin: string): string | string[] {
  const value = corsOrigin.trim();
  if (!value) return '*';
  if (value === '*') return '*';
  return value
    .split(',')
    .map((origin) => origin.trim())
    .filter(Boolean);
}

/** Safe logger options: request headers that could carry credentials are redacted. */
export function loggerOptions(level: string) {
  return {
    level,
    redact: {
      paths: ['req.headers.authorization', 'req.headers.cookie', 'res.headers["set-cookie"]'],
      censor: '[Redacted]',
    },
  };
}
