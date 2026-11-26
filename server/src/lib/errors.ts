export type ErrorCode =
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'VALIDATION'
  | 'CONFLICT'
  | 'ALREADY_IN_FAMILY'
  | 'INVITE_INVALID'
  | 'INVITE_EXPIRED'
  | 'DEVICE_OFFLINE'
  | 'RATE_LIMITED'
  // Not part of the M1 contract error list; used for unexpected server failures.
  | 'INTERNAL';

export class AppError extends Error {
  readonly statusCode: number;
  readonly code: ErrorCode;

  constructor(statusCode: number, code: ErrorCode, message: string) {
    super(message);
    this.name = 'AppError';
    this.statusCode = statusCode;
    this.code = code;
  }
}

export function unauthorized(message = 'Authentication required'): AppError {
  return new AppError(401, 'UNAUTHORIZED', message);
}

export function forbidden(message = 'You do not have permission to do this'): AppError {
  return new AppError(403, 'FORBIDDEN', message);
}

export function notFound(message = 'Resource not found'): AppError {
  return new AppError(404, 'NOT_FOUND', message);
}

export function validationError(message: string): AppError {
  return new AppError(400, 'VALIDATION', message);
}

export function conflict(message = 'Resource conflict'): AppError {
  return new AppError(409, 'CONFLICT', message);
}

export function alreadyInFamily(message = 'You are already in a family'): AppError {
  return new AppError(409, 'ALREADY_IN_FAMILY', message);
}

export function inviteInvalid(message = 'Invite code is invalid'): AppError {
  return new AppError(400, 'INVITE_INVALID', message);
}

export function inviteExpired(message = 'Invite code has expired'): AppError {
  return new AppError(400, 'INVITE_EXPIRED', message);
}

export function deviceOffline(message = 'Device is offline'): AppError {
  return new AppError(409, 'DEVICE_OFFLINE', message);
}

export function errorBody(code: ErrorCode, message: string): { error: { code: ErrorCode; message: string } } {
  return { error: { code, message } };
}
