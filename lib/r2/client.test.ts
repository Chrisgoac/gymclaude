// @vitest-environment node
import { S3Client, PutObjectCommand, DeleteObjectCommand } from '@aws-sdk/client-s3';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  deletePrivateObject,
  putPrivateObject,
  r2MediaStorage,
  r2PrivateConfigured,
  resetR2ClientForTests,
  signPrivateRead,
} from './client';

const ENV = {
  R2_ACCOUNT_ID: 'acct123',
  R2_ACCESS_KEY_ID: 'AKIDTEST',
  R2_SECRET_ACCESS_KEY: 'secret-test',
  R2_BUCKET: 'gymlog-test',
};

describe('private R2 helpers', () => {
  beforeEach(() => {
    for (const [k, v] of Object.entries(ENV)) vi.stubEnv(k, v);
    vi.stubEnv('R2_PUBLIC_URL', '');
    resetR2ClientForTests();
  });
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
    resetR2ClientForTests();
  });

  it('is configured without a public URL', () => {
    expect(r2PrivateConfigured()).toBe(true);
    vi.stubEnv('R2_BUCKET', '');
    expect(r2PrivateConfigured()).toBe(false);
  });

  it('signs a short-lived GET locally, without any network call', async () => {
    const send = vi.spyOn(S3Client.prototype, 'send');
    const signed = new URL(await signPrivateRead('users/abc/progress/1', 300));
    expect(send).not.toHaveBeenCalled();
    expect(signed.hostname).toBe('gymlog-test.acct123.r2.cloudflarestorage.com');
    expect(signed.pathname).toBe('/users/abc/progress/1');
    expect(signed.searchParams.get('X-Amz-Expires')).toBe('300');
    expect(signed.searchParams.get('X-Amz-Signature')).toMatch(/^[0-9a-f]{64}$/);
  });

  it('puts objects privately and deletes them through the S3 client', async () => {
    const send = vi.spyOn(S3Client.prototype, 'send').mockResolvedValue({} as never);
    await putPrivateObject('users/abc/exercise/1', new Uint8Array([1]), 'image/jpeg');
    await deletePrivateObject('users/abc/exercise/1');
    const [put] = send.mock.calls[0] as unknown as [PutObjectCommand];
    const [del] = send.mock.calls[1] as unknown as [DeleteObjectCommand];
    expect(put).toBeInstanceOf(PutObjectCommand);
    expect(put.input).toMatchObject({ Bucket: 'gymlog-test', Key: 'users/abc/exercise/1', ContentType: 'image/jpeg', ContentDisposition: 'inline' });
    expect(put.input).not.toHaveProperty('ACL');
    expect(del).toBeInstanceOf(DeleteObjectCommand);
    expect(del.input).toEqual({ Bucket: 'gymlog-test', Key: 'users/abc/exercise/1' });
    expect(r2MediaStorage.signRead).toBe(signPrivateRead);
  });
});
