'use client';

import { useRef, useState } from 'react';
import { exportData, importData, type BackupFile } from '@/lib/repositories/backup';
import { Button } from '@/components/ui/button';
import { GymManager } from '@/components/gym-manager';
import { RoutineOrderManager } from '@/components/routine-order-manager';
import { useSuggestNextRoutine, useModoProgresion, useIncrementos } from '@/lib/settings';
import { EQUIPMENTS } from '@/lib/db/types';
import { equipmentLabel } from '@/lib/labels';
import type { ModoProgresion } from '@/lib/progresion';

const MODO_LABEL: Record<ModoProgresion, string> = {
  doble: 'Doble progresión (rango de reps)',
  objetivo: 'Objetivo + incremento',
  repite: 'Repite o sube',
  off: 'Desactivada',
};

export default function AjustesPage() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [mensaje, setMensaje] = useState('');
  const [sugerir, setSugerir] = useSuggestNextRoutine();
  const [modo, setModo] = useModoProgresion();
  const [incrementos, setIncrementos] = useIncrementos();

  async function exportar() {
    try {
      const backup = await exportData();
      const blob = new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `gymlog-backup-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
      setMensaje('Copia exportada.');
    } catch {
      setMensaje('No se pudo exportar la copia.');
    }
  }

  async function importar(file: File) {
    try {
      const backup = JSON.parse(await file.text()) as BackupFile;
      await importData(backup);
      setMensaje('Copia importada correctamente.');
    } catch {
      setMensaje('No se pudo importar: fichero no válido.');
    }
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Ajustes</h1>
      <GymManager />

      <section className="space-y-3">
        <h2 className="label-mono text-[11px] text-muted-foreground">Rutinas</h2>
        <label className="brutal-box flex items-center justify-between gap-3 px-3 py-2.5">
          <span className="font-semibold">Sugerir siguiente rutina</span>
          <input
            type="checkbox"
            className="size-5 accent-[var(--primary)]"
            checked={sugerir}
            onChange={(e) => setSugerir(e.target.checked)}
          />
        </label>
        <RoutineOrderManager />
      </section>

      <section className="space-y-3">
        <h2 className="label-mono text-[11px] text-muted-foreground">Progresión</h2>
        <label className="brutal-box flex items-center justify-between gap-3 px-3 py-2.5">
          <span className="font-semibold">Modo</span>
          <select
            className="border-2 border-foreground bg-card p-1 text-sm"
            value={modo}
            onChange={(e) => setModo(e.target.value as ModoProgresion)}
          >
            {(Object.keys(MODO_LABEL) as ModoProgresion[]).map((m) => (
              <option key={m} value={m}>{MODO_LABEL[m]}</option>
            ))}
          </select>
        </label>
        <div className="brutal-box divide-y-2 divide-foreground">
          {EQUIPMENTS.filter((eq) => eq !== 'peso_corporal' && eq !== 'otro').map((eq) => (
            <label key={eq} className="flex items-center justify-between gap-3 px-3 py-2">
              <span className="text-sm">{equipmentLabel[eq]}</span>
              <input
                type="number"
                step="0.5"
                min="0"
                className="w-20 border-2 border-foreground bg-card p-1 text-right text-sm tabular-nums"
                value={incrementos[eq]}
                onChange={(e) => setIncrementos({ [eq]: Math.max(0, Number(e.target.value)) })}
              />
            </label>
          ))}
        </div>
        <p className="label-mono text-[10px] text-muted-foreground">
          Salto por defecto cuando no hay historial. Con historial, la app lo deduce de tus pesos.
        </p>
      </section>

      <section className="space-y-3">
        <h2 className="text-sm font-semibold uppercase text-muted-foreground">Copia de seguridad</h2>
        <Button onClick={exportar} className="w-full">Exportar copia (JSON)</Button>
        <Button variant="secondary" className="w-full" onClick={() => inputRef.current?.click()}>
          Importar copia
        </Button>
        <input
          ref={inputRef}
          type="file"
          accept="application/json"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) void importar(file);
            e.target.value = ''; // permite reimportar el mismo fichero
          }}
        />
        {mensaje && <p className="text-sm text-muted-foreground">{mensaje}</p>}
      </section>
    </div>
  );
}
