import { describe, it, expect } from 'vitest';
import { inferirSalto, INCREMENTO_DEFAULTS, calcularSugerencia, describeMotivo } from '@/lib/progresion';

const defaults = INCREMENTO_DEFAULTS;

describe('inferirSalto', () => {
  it('GCD de las diferencias entre pesos distintos', () => {
    expect(inferirSalto([40, 45, 50, 60], { equipamiento: 'maquina', defaults })).toBe(5);
  });
  it('soporta saltos de 2,5', () => {
    expect(inferirSalto([40, 42.5, 45], { equipamiento: 'barra', defaults })).toBe(2.5);
  });
  it('redondea ruido al valor sano más cercano (coincidencia exacta: 7,5)', () => {
    expect(inferirSalto([40, 47.5], { equipamiento: 'maquina', defaults })).toBe(7.5);
  });
  it('snap real: una diferencia no-sana se redondea al paso sano más cercano', () => {
    // 48 − 40 = 8 → no está en SANE_STEPS → snap a 7.5
    expect(inferirSalto([40, 48], { equipamiento: 'maquina', defaults })).toBe(7.5);
  });
  it('sin historial suficiente → default por equipamiento', () => {
    expect(inferirSalto([], { equipamiento: 'mancuerna', defaults })).toBe(2);
    expect(inferirSalto([40], { equipamiento: 'maquina', defaults })).toBe(5);
  });
  it('ignora pesos 0 y duplicados', () => {
    expect(inferirSalto([0, 40, 40, 45], { equipamiento: 'barra', defaults })).toBe(5);
  });
  it('el override gana sobre la inferencia', () => {
    expect(inferirSalto([40, 45, 50], { equipamiento: 'maquina', defaults, override: 1.25 })).toBe(1.25);
  });
});

const objetivo = { repsObjetivo: 12, repsObjetivoMin: 8 };

describe('calcularSugerencia', () => {
  it('off → repite el último peso/reps sin badge', () => {
    const s = calcularSugerencia({ modo: 'off', ultimo: [{ peso: 40, reps: 12 }], objetivo, salto: 5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 40, repsSugeridas: 12, motivo: 'off' });
  });
  it('sin objetivo (entreno libre) → repite el último, motivo libre', () => {
    const s = calcularSugerencia({ modo: 'objetivo', ultimo: [{ peso: 30, reps: 10 }], objetivo: undefined, salto: 5, esCorporal: false });
    expect(s.pesoSugerido).toBe(30);
    expect(s.motivo).toBe('libre');
  });
  it('sin historial → peso 0, reps = objetivo, motivo sin-historial', () => {
    const s = calcularSugerencia({ modo: 'objetivo', ultimo: undefined, objetivo, salto: 5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 0, repsSugeridas: 12, motivo: 'sin-historial' });
  });
  it('objetivo · éxito (todas las series al objetivo) → +salto', () => {
    const s = calcularSugerencia({ modo: 'objetivo', ultimo: [{ peso: 40, reps: 12 }, { peso: 40, reps: 12 }], objetivo, salto: 5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 45, repsSugeridas: 12, motivo: 'subio-peso' });
  });
  it('objetivo · fallo parcial (una serie no llega) → repite peso', () => {
    const s = calcularSugerencia({ modo: 'objetivo', ultimo: [{ peso: 40, reps: 12 }, { peso: 40, reps: 10 }], objetivo, salto: 5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 40, repsSugeridas: 12, motivo: 'repite' });
  });
  it('doble · éxito (todas al tope) → +salto y reps al mín del rango', () => {
    const s = calcularSugerencia({ modo: 'doble', ultimo: [{ peso: 40, reps: 12 }, { peso: 40, reps: 12 }], objetivo, salto: 2.5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 42.5, repsSugeridas: 8, motivo: 'subio-peso' });
  });
  it('doble · sin llegar al tope → mismo peso, +1 rep hacia el tope', () => {
    const s = calcularSugerencia({ modo: 'doble', ultimo: [{ peso: 40, reps: 9 }, { peso: 40, reps: 9 }], objetivo, salto: 2.5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 40, repsSugeridas: 10, motivo: 'subio-reps' });
  });
  it('doble · rango por defecto cuando falta repsObjetivoMin (tope−4)', () => {
    const s = calcularSugerencia({ modo: 'doble', ultimo: [{ peso: 40, reps: 12 }], objetivo: { repsObjetivo: 12 }, salto: 2.5, esCorporal: false });
    expect(s.repsSugeridas).toBe(8);
  });
  it('repite · éxito → +salto', () => {
    const s = calcularSugerencia({ modo: 'repite', ultimo: [{ peso: 50, reps: 12 }], objetivo, salto: 5, esCorporal: false });
    expect(s.pesoSugerido).toBe(55);
    expect(s.motivo).toBe('subio-peso');
  });
  it('peso corporal · éxito → no toca peso, sube reps', () => {
    const s = calcularSugerencia({ modo: 'objetivo', ultimo: [{ peso: 0, reps: 12 }], objetivo, salto: 5, esCorporal: true });
    expect(s.pesoSugerido).toBe(0);
    expect(s.repsSugeridas).toBe(13);
    expect(s.motivo).toBe('subio-reps');
  });
});

describe('describeMotivo', () => {
  it('subio-peso muestra el salto', () => {
    expect(describeMotivo({ pesoSugerido: 45, repsSugeridas: 12, motivo: 'subio-peso' }, 5)).toBe('▲ +5 kg');
  });
  it('subio-reps', () => {
    expect(describeMotivo({ pesoSugerido: 40, repsSugeridas: 10, motivo: 'subio-reps' }, 2.5)).toBe('▲ +1 rep');
  });
  it('repite', () => {
    expect(describeMotivo({ pesoSugerido: 40, repsSugeridas: 12, motivo: 'repite' }, 5)).toBe('= repite');
  });
  it('sin badge para off/libre/sin-historial', () => {
    expect(describeMotivo({ pesoSugerido: 0, repsSugeridas: 0, motivo: 'off' }, 5)).toBeNull();
    expect(describeMotivo({ pesoSugerido: 0, repsSugeridas: 0, motivo: 'libre' }, 5)).toBeNull();
    expect(describeMotivo({ pesoSugerido: 0, repsSugeridas: 0, motivo: 'sin-historial' }, 5)).toBeNull();
  });
});
