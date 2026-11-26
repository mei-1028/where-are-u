import fp from 'fastify-plugin';
import { ZodError } from 'zod';
import { AppError, errorBody, type ErrorCode } from '../lib/errors.ts';

const STATUS_CODE_MAP: Record<number, ErrorCode> = {
  400: 'VALIDATION',
  401: 'UNAUTHORIZED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  409: 'CONFLICT',
  429: 'RATE_LIMITED',
};

function zodMessage(error: ZodError): string {
  return error.issues
    .map((issue) => {
      const path = issue.path.length > 0 ? issue.path.join('.') : 'body';
      return `${path}: ${issue.message}`;
    })
    .join('; ');
}

/**
 * Installs the uniform { error: { code, message } } response shape for every
 * failure, including unmatched routes. Internal errors never echo internals.
 */
export const errorHandlerPlugin = fp(
  async (app) => {
    app.setErrorHandler((error, request, reply) => {
      if (error instanceof AppError) {
        return reply.code(error.statusCode).send(errorBody(error.code, error.message));
      }

      if (error instanceof ZodError) {
        return reply.code(400).send(errorBody('VALIDATION', zodMessage(error)));
      }

      // Fastify types the error as unknown in v5; narrow what we need.
      const maybeError = error as {
        statusCode?: unknown;
        message?: unknown;
        validation?: unknown;
      };
      const message = typeof maybeError.message === 'string' ? maybeError.message : 'Request failed';

      if (maybeError.validation) {
        return reply.code(400).send(errorBody('VALIDATION', message));
      }

      const statusCode = typeof maybeError.statusCode === 'number' ? maybeError.statusCode : 500;

      if (statusCode === 415) {
        return reply.code(400).send(errorBody('VALIDATION', 'Unsupported content type'));
      }

      if (statusCode < 500) {
        const code = STATUS_CODE_MAP[statusCode] ?? 'VALIDATION';
        return reply.code(statusCode).send(errorBody(code, message));
      }

      request.log.error({ err: error }, 'Unhandled request error');
      return reply.code(500).send(errorBody('INTERNAL', 'Internal server error'));
    });

    app.setNotFoundHandler((request, reply) => {
      return reply.code(404).send(errorBody('NOT_FOUND', `Route ${request.method} ${request.url} not found`));
    });
  },
  { name: 'error-handler' },
);
