// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { isNetworkOnlyRequest } from './network-only';

const match = (href: string, sameOrigin = true) => isNetworkOnlyRequest({ sameOrigin, url: new URL(href) });

describe('service worker network-only routes', () => {
  it('sends same-origin private media reads and the v1 API to the network', () => {
    expect(match('https://gym.chrisgoac.dev/api/media/read/abc')).toBe(true);
    expect(match('https://gym.chrisgoac.dev/api/v1/media/read/abc')).toBe(true);
    expect(match('https://gym.chrisgoac.dev/api/v1/sync/pull?cursor=1')).toBe(true);
  });

  it('leaves other paths and cross-origin requests to the default strategies', () => {
    expect(match('https://gym.chrisgoac.dev/api/sync/pull')).toBe(false);
    expect(match('https://gym.chrisgoac.dev/api/media/readme')).toBe(false);
    expect(match('https://gym.chrisgoac.dev/progreso')).toBe(false);
    expect(match('https://other.example/api/media/read/abc', false)).toBe(false);
  });

  it('registers the network-only rule before defaultCache (first match wins)', () => {
    const sw = readFileSync('app/sw.ts', 'utf8');
    const rule = sw.indexOf('isNetworkOnlyRequest');
    const defaults = sw.indexOf('...defaultCache');
    expect(rule).toBeGreaterThan(-1);
    expect(defaults).toBeGreaterThan(rule);
    expect(sw).toMatch(/new NetworkOnly\(\)/);
  });
});
