import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { isNetworkOnlyRequest } from '@/lib/sw/network-only';

const h = vi.hoisted(() => ({ approved: false }));
vi.mock('./status', () => ({
  get PRIVACY_POLICY_APPROVED() {
    return h.approved;
  },
  PRIVACY_POLICY_LAST_UPDATED: '2026-10-11',
}));

import PrivacyPage from './page';

const root = process.cwd();
const read = (path: string) => readFileSync(join(root, path), 'utf8');
/** Compare text ignoring all whitespace (JSX and Markdown wrap lines differently). */
const squash = (text: string) => text.replace(/\s+/g, '');
/** The Markdown subset used in the policy: list markers, **bold**, `code` and [text](url) links. */
const stripMarkdown = (md: string) =>
  md
    .replace(/^- /gm, '')
    .replace(/\*\*/g, '')
    .replace(/`/g, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1');

afterEach(() => {
  cleanup();
  h.approved = false;
});

describe('/privacy (public page)', () => {
  it('shows the policy with the draft marker while not approved', () => {
    render(<PrivacyPage />);
    expect(screen.getByRole('heading', { level: 1, name: /política de privacidad/i })).toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent(/borrador pendiente de aprobación/i);
    expect(screen.getByText(/11 de octubre de 2026/)).toBeInTheDocument();
  });

  it('hides the draft marker once approved', () => {
    h.approved = true;
    render(<PrivacyPage />);
    expect(screen.queryByText(/borrador pendiente de aprobación/i)).not.toBeInTheDocument();
  });

  it('names every processor, the AI limits and links to deletion and Play subscriptions', () => {
    render(<PrivacyPage />);
    for (const name of ['Clerk', 'Vercel', 'Neon', 'Cloudflare R2', 'DeepSeek', 'Google Play']) {
      expect(screen.getByText(`${name}:`)).toBeInTheDocument();
    }
    expect(screen.getByText(/no son consejo médico/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /página de borrado de cuenta/i })).toHaveAttribute('href', '/delete-account');
    expect(screen.getByRole('link', { name: /suscripciones de google play/i })).toHaveAttribute(
      'href',
      expect.stringMatching(/^https:\/\/play\.google\.com\/store\/account\/subscriptions\?package=com\.chrisgoac\.gymlog/),
    );
  });

  it('has the same sections, word for word, as docs/play/privacy-policy.md', () => {
    render(<PrivacyPage />);
    const pageSections = screen.getAllByRole('heading', { level: 2 }).map((h2) => ({
      title: h2.textContent?.trim() ?? '',
      text: squash((h2.parentElement?.textContent ?? '').slice((h2.textContent ?? '').length)),
    }));
    const doc = read('docs/play/privacy-policy.md');
    const start = doc.indexOf('## Texto publicado');
    const published = doc.slice(start, doc.indexOf('\n## ', start + 1));
    const docSections = published.split(/^### /m).slice(1).map((chunk) => {
      const [title, ...body] = chunk.split('\n');
      return { title: title.trim(), text: squash(stripMarkdown(body.join('\n'))) };
    });
    expect(docSections.length).toBeGreaterThan(0);
    expect(pageSections).toEqual(docSections);
  });

  it('cannot be marked approved while owner placeholders remain', async () => {
    // Uses the real constant: flipping PRIVACY_POLICY_APPROVED to true fails here until every
    // "[confirmar por el dueño]" is filled in.
    const actual = await vi.importActual<typeof import('./status')>('./status');
    h.approved = actual.PRIVACY_POLICY_APPROVED;
    const { container } = render(<PrivacyPage />);
    if (actual.PRIVACY_POLICY_APPROVED) expect(container.textContent).not.toMatch(/\[confirmar/);
    else expect(container.textContent).toMatch(/borrador pendiente de aprobación/i);
  });

  it('is reachable without a session and is not a network-only (private) path', () => {
    // proxy.ts only attaches Clerk; it never protects routes, so /privacy needs no session.
    expect(read('proxy.ts')).not.toMatch(/protect|createRouteMatcher/);
    // The service worker serves it network-first like any public page, never from a private rule.
    expect(isNetworkOnlyRequest({ sameOrigin: true, url: new URL('https://gym.chrisgoac.dev/privacy') })).toBe(false);
  });

  it('is the URL the Android app opens', () => {
    expect(read('android/app/build.gradle.kts')).toContain('quoted("$webBaseUrl/privacy")');
  });
});
