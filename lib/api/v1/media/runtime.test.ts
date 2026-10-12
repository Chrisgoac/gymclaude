// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest';
import { getMediaService } from './runtime';

describe('getMediaService', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('fails closed with 503 MEDIA_UNAVAILABLE when the private bucket is not configured', () => {
    vi.stubEnv('R2_ACCOUNT_ID', 'acct');
    vi.stubEnv('R2_ACCESS_KEY_ID', 'id');
    vi.stubEnv('R2_SECRET_ACCESS_KEY', 'secret');
    vi.stubEnv('R2_BUCKET', 'public-bucket');
    vi.stubEnv('R2_PRIVATE_BUCKET', '');
    expect(() => getMediaService()).toThrow(
      expect.objectContaining({ status: 503, code: 'MEDIA_UNAVAILABLE', retryable: true }),
    );
  });
});
