export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryable: boolean,
    readonly details?: Record<string, string[]>,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export class RateLimitedError extends ApiError {
  constructor(readonly retryAfterSeconds: number) {
    super(429, 'RATE_LIMITED', 'Too many requests', true, {
      retryAfterSeconds: [String(retryAfterSeconds)],
    });
    this.name = 'RateLimitedError';
  }
}

export const authRequired = () => new ApiError(401, 'AUTH_REQUIRED', 'Authentication required', false);
