// @vitest-environment node
import { S3Client, PutObjectCommand, DeleteObjectCommand, ListObjectsV2Command } from '@aws-sdk/client-s3';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  deleteLegacyObject,
  deletePrivateObject,
  listLegacyObjects,
  listPrivateObjects,
  r2AccountStorage,
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
  R2_BUCKET: 'gymlog-public-test',
  R2_PRIVATE_BUCKET: 'gymlog-test',
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

  it('is configured by the private bucket, without a public URL or the public bucket', () => {
    vi.stubEnv('R2_BUCKET', '');
    expect(r2PrivateConfigured()).toBe(true);
    vi.stubEnv('R2_PRIVATE_BUCKET', '');
    expect(r2PrivateConfigured()).toBe(false);
  });

  it('never falls back to the public bucket when the private one is unset', async () => {
    vi.stubEnv('R2_PRIVATE_BUCKET', '');
    const send = vi.spyOn(S3Client.prototype, 'send').mockResolvedValue({} as never);
    await expect(putPrivateObject('users/abc/exercise/1', new Uint8Array([1]), 'image/jpeg')).rejects.toThrow(/R2_PRIVATE_BUCKET/);
    await expect(signPrivateRead('users/abc/exercise/1', 300)).rejects.toThrow(/R2_PRIVATE_BUCKET/);
    await expect(deletePrivateObject('users/abc/exercise/1')).rejects.toThrow(/R2_PRIVATE_BUCKET/);
    expect(send).not.toHaveBeenCalled();
  });

  it('deletes legacy PWA keys from the public bucket', async () => {
    const send = vi.spyOn(S3Client.prototype, 'send').mockResolvedValue({} as never);
    await deleteLegacyObject('user_1/e1/x.jpg');
    const [del] = send.mock.calls[0] as unknown as [DeleteObjectCommand];
    expect(del.input).toEqual({ Bucket: 'gymlog-public-test', Key: 'user_1/e1/x.jpg' });
    expect(r2MediaStorage.removeLegacy).toBe(deleteLegacyObject);
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

  it('lists every key under a prefix of the private bucket, following pagination', async () => {
    const send = vi
      .spyOn(S3Client.prototype, 'send')
      .mockResolvedValueOnce({ Contents: [{ Key: 'users/abc/progress/1' }], IsTruncated: true, NextContinuationToken: 't1' } as never)
      .mockResolvedValueOnce({ Contents: [{ Key: 'users/abc/exercise/2' }], IsTruncated: false } as never);
    expect(await listPrivateObjects('users/abc/')).toEqual(['users/abc/progress/1', 'users/abc/exercise/2']);
    const [first] = send.mock.calls[0] as unknown as [ListObjectsV2Command];
    const [second] = send.mock.calls[1] as unknown as [ListObjectsV2Command];
    expect(first).toBeInstanceOf(ListObjectsV2Command);
    expect(first.input).toEqual({ Bucket: 'gymlog-test', Prefix: 'users/abc/', ContinuationToken: undefined });
    expect(second.input).toMatchObject({ ContinuationToken: 't1' });
  });

  it('lists legacy keys from the public bucket and refuses prefixes without a trailing slash', async () => {
    const send = vi.spyOn(S3Client.prototype, 'send').mockResolvedValue({ Contents: [{ Key: 'user_1/e/x.jpg' }] } as never);
    expect(await listLegacyObjects('user_1/')).toEqual(['user_1/e/x.jpg']);
    expect((send.mock.calls[0] as unknown as [ListObjectsV2Command])[0].input).toMatchObject({ Bucket: 'gymlog-public-test', Prefix: 'user_1/' });
    // `user_1` without "/" would also match `user_10/…` of another user.
    await expect(listLegacyObjects('user_1')).rejects.toThrow(/end with/);
    await expect(listPrivateObjects('users/abc')).rejects.toThrow(/end with/);
    vi.stubEnv('R2_BUCKET', '');
    await expect(listLegacyObjects('user_1/')).rejects.toThrow(/R2_BUCKET/);
    expect(r2AccountStorage).toMatchObject({ listPrivate: listPrivateObjects, listLegacy: listLegacyObjects });
  });
});
