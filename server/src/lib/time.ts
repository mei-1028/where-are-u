export const INVITE_TTL_MS = 24 * 60 * 60 * 1000;
export const LOCATE_DEDUPE_MS = 30 * 1000;
/** Reject device clocks that are more than 5 minutes ahead of the server. */
export const MAX_RECORDED_AT_FUTURE_MS = 5 * 60 * 1000;

export function inviteExpiryFrom(now = new Date()): Date {
  return new Date(now.getTime() + INVITE_TTL_MS);
}

export function addMilliseconds(date: Date, milliseconds: number): Date {
  return new Date(date.getTime() + milliseconds);
}
