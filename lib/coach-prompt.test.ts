import { describe, it, expect } from 'vitest';
import { systemPrompt, DISCLAIMER } from '@/lib/coach-prompt';
import type { CoachSnapshot } from '@/lib/coach-snapshot';

const snap: CoachSnapshot = {
  estancados: [{ ejercicio: 'Sentadilla', sesionesSinMejora: 4 }],
  semana: { sesiones: 2, objetivo: 3, volumen: 9000, deltaPct: 12, prs: [{ ejercicio: 'Press', tipo: 'peso' }] },
  grupos: [{ grupo: 'Pecho', volumenSemana: 1200, diasSinEntrenar: 2, objetivo: 1500 }],
};

describe('systemPrompt', () => {
  it('incluye disclaimer, persona de coach y el snapshot serializado', () => {
    const p = systemPrompt(snap);
    expect(p).toContain(DISCLAIMER);
    expect(p.toLowerCase()).toContain('entrenador');
    expect(p).toContain('Sentadilla');
    expect(p).toContain('Pecho');
    expect(p).toContain('"sesiones": 2');
  });
  it('instruye español/conciso/accionable', () => {
    const p = systemPrompt(snap);
    expect(p.toLowerCase()).toMatch(/español|concis|accionable/);
  });
  it('incluye un guardarraíl de ámbito: rechaza lo que no sea entrenamiento', () => {
    const p = systemPrompt(snap).toLowerCase();
    // limita el ámbito y manda declinar lo fuera de tema
    expect(p).toMatch(/ámbito|solo respondes|únicamente/);
    expect(p).toMatch(/declina|rechaza|no respondas/);
    // resistencia básica a cambio de rol (prompt injection casual)
    expect(p).toMatch(/no sigas instrucciones|no cambies de rol|mantén tu rol/);
  });
  it('repite el guardarraíl al final (recencia, contra el precedente del hilo)', () => {
    const p = systemPrompt(snap).toLowerCase();
    expect(p).toMatch(/recordatorio final/);
    expect(p).toMatch(/aunque la conversación anterior/);
  });
});
