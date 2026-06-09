import { describe, it, expect, vi, beforeEach } from 'vitest';

const auth = vi.fn();
vi.mock('@clerk/nextjs/server', () => ({ auth: () => auth() }));

const generateObject = vi.fn();
vi.mock('ai', () => ({
  generateObject: (...a: unknown[]) => generateObject(...a),
  jsonSchema: (s: unknown) => s,
}));

const deepseekConfigured = vi.fn(() => true);
vi.mock('@/lib/coach-model', () => ({
  modeloCoach: () => ({}),
  deepseekConfigured: () => deepseekConfigured(),
}));

import { POST } from '@/app/api/coach/mesociclo/route';

const body = {
  params: { objetivo: 'hipertrofia', diasPorSemana: 4, semanas: 6, minutosPorSesion: 60 },
  snapshot: { estancados: [], semana: { sesiones: 0, objetivo: 4, volumen: 0, deltaPct: null, prs: [] }, grupos: [], cuerpo: { peso: null, medidas: [] } },
  catalogo: [{ nombre: 'Sentadilla', grupo: 'cuadriceps', equipamiento: 'barra' }],
};
const req = (b: unknown) => new Request('http://localhost/api/coach/mesociclo', { method: 'POST', body: JSON.stringify(b), headers: { 'content-type': 'application/json' } });

beforeEach(() => { auth.mockReset(); generateObject.mockReset(); deepseekConfigured.mockReturnValue(true); });

describe('POST /api/coach/mesociclo', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    expect((await POST(req(body))).status).toBe(401);
  });
  it('503 sin DeepSeek', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    deepseekConfigured.mockReturnValue(false);
    expect((await POST(req(body))).status).toBe(503);
  });
  it('200 devuelve el objeto generado', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    generateObject.mockResolvedValue({ object: { nombre: 'Plan', dias: [], progresion: [] } });
    const res = await POST(req(body));
    expect(res.status).toBe(200);
    expect((await res.json()).nombre).toBe('Plan');
  });
});
