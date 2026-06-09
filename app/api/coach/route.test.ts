import { describe, it, expect, vi, beforeEach } from 'vitest';

const auth = vi.fn();
vi.mock('@clerk/nextjs/server', () => ({ auth: () => auth() }));

const deepseekConfigured = vi.fn(() => true);
vi.mock('@/lib/coach-model', () => ({
  deepseekConfigured: () => deepseekConfigured(),
  modeloCoach: () => 'fake-model',
  MODELO_COACH: 'deepseek-chat',
}));

const streamText = vi.fn((...args: unknown[]) => {
  void args;
  return { toUIMessageStreamResponse: () => new Response('stream-ok') };
});
const convertToModelMessages = vi.fn((m: unknown) => m);
vi.mock('ai', () => ({
  streamText: (opts: unknown) => streamText(opts),
  convertToModelMessages: (m: unknown) => convertToModelMessages(m),
}));

import { POST } from '@/app/api/coach/route';

const snapshot = {
  estancados: [{ ejercicio: 'Sentadilla', sesionesSinMejora: 4 }],
  semana: { sesiones: 2, objetivo: 3, volumen: 9000, deltaPct: 12, prs: [] },
  grupos: [],
};
function req(body: unknown): Request {
  return new Request('http://localhost/api/coach', { method: 'POST', body: JSON.stringify(body) });
}

beforeEach(() => {
  auth.mockReset();
  deepseekConfigured.mockReturnValue(true);
  streamText.mockClear();
});

describe('POST /api/coach', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    expect((await POST(req({ messages: [], snapshot }))).status).toBe(401);
  });
  it('503 si falta DEEPSEEK_API_KEY', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    deepseekConfigured.mockReturnValue(false);
    expect((await POST(req({ messages: [], snapshot }))).status).toBe(503);
  });
  it('con sesión + key: streamText recibe el system prompt con el snapshot y devuelve el stream', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await POST(req({ messages: [{ role: 'user', parts: [{ type: 'text', text: 'hola' }] }], snapshot }));
    expect(res.status).toBe(200);
    expect(streamText).toHaveBeenCalledTimes(1);
    const opts = streamText.mock.calls[0][0] as { system: string };
    expect(opts.system).toContain('Sentadilla');
    expect(await res.text()).toBe('stream-ok');
  });
  it('400 con body no-JSON', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await POST(new Request('http://localhost/api/coach', { method: 'POST', body: 'no-json{' }));
    expect(res.status).toBe(400);
  });
  it('400 si messages no es un array', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await POST(new Request('http://localhost/api/coach', { method: 'POST', body: JSON.stringify({ snapshot: {} }) }));
    expect(res.status).toBe(400);
  });
});
