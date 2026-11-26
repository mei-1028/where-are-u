import { describe, expect, it } from 'vitest';
import { loadConfig } from '../src/lib/config.ts';

const VALID_SECRET = 'config-test-only-signing-secret-0123456789abcdef';

function load(jwtSecret: string) {
  return loadConfig({ databaseUrl: 'file:./config-test.db', jwtSecret });
}

describe('JWT secret configuration', () => {
  it('accepts a long random-looking secret', () => {
    const config = load(VALID_SECRET);
    expect(config.jwtSecret).toBe(VALID_SECRET);
  });

  it('rejects empty and too-short secrets', () => {
    expect(() => load('')).toThrow(/JWT_SECRET/);
    expect(() => load(VALID_SECRET.slice(0, 31))).toThrow(/JWT_SECRET/);
  });

  it('rejects known example/placeholder values', () => {
    expect(() => load('replace-with-a-long-random-secret-0123456789')).toThrow(/placeholder/i);
    expect(() => load('change-me-before-production-0123456789')).toThrow(/placeholder/i);
    expect(() => load('your-secret-0123456789abcdefghijklmnop')).toThrow(/placeholder/i);
    expect(() => load(`${'{'}{placeholder-0123456789abcdef}}`)).toThrow(/placeholder/i);
  });

  it('rejects trivially low-entropy secrets', () => {
    expect(() => load('a'.repeat(64))).toThrow(/random/i);
  });
});
