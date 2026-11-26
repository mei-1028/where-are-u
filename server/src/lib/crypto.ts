import { createHash, randomBytes } from 'node:crypto';
import * as argon2 from 'argon2';

export function sha256Hex(value: string): string {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

export async function hashPassword(password: string): Promise<string> {
  return argon2.hash(password, { type: argon2.argon2id });
}

export async function verifyPassword(passwordHash: string, password: string): Promise<boolean> {
  try {
    return await argon2.verify(passwordHash, password);
  } catch {
    // Malformed / legacy hashes must never crash the login flow.
    return false;
  }
}

export interface GeneratedDeviceToken {
  token: string;
  tokenHash: string;
}

/** 32 random bytes, base64url encoded. Only the SHA-256 hash is persisted. */
export function generateDeviceToken(): GeneratedDeviceToken {
  const token = randomBytes(32).toString('base64url');
  return { token, tokenHash: sha256Hex(token) };
}
