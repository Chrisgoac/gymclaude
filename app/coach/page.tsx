'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { listMessages } from '@/lib/repositories/coach';
import { CoachChat } from '@/components/coach-chat';

export default function CoachPage() {
  const hilo = useLiveQuery(() => listMessages(), []);
  return (
    <div className="space-y-6">
      <div>
        <p className="label-mono text-[11px] text-muted-foreground">Tu entrenador IA</p>
        <h1 className="text-5xl">Coach</h1>
      </div>
      {hilo === undefined ? (
        <p className="text-muted-foreground">Cargando…</p>
      ) : (
        <CoachChat seed={hilo} />
      )}
    </div>
  );
}
