'use client';

import { useEffect, useState } from 'react';
import { useAuth } from '@clerk/nextjs';
import { runSync } from '@/lib/sync/sync';
import { httpTransport } from '@/lib/sync/http-transport';

type Estado = 'idle' | 'syncing' | 'offline' | 'error';

export function SyncProvider() {
  const { isSignedIn } = useAuth();
  const [estado, setEstado] = useState<Estado>('idle');

  useEffect(() => {
    if (!isSignedIn) return;
    let cancelado = false;

    async function sync() {
      if (typeof navigator !== 'undefined' && !navigator.onLine) {
        if (!cancelado) setEstado('offline');
        return;
      }
      if (!cancelado) setEstado('syncing');
      try {
        await runSync(httpTransport);
        if (!cancelado) setEstado('idle');
      } catch {
        if (!cancelado) setEstado('error');
      }
    }

    void sync();
    const onOnline = () => void sync();
    window.addEventListener('online', onOnline);
    const intervalo = setInterval(() => void sync(), 30000);
    return () => {
      cancelado = true;
      window.removeEventListener('online', onOnline);
      clearInterval(intervalo);
    };
  }, [isSignedIn]);

  if (!isSignedIn) return null;
  const etiqueta =
    estado === 'syncing' ? 'Sync…' : estado === 'offline' ? 'Offline' : estado === 'error' ? 'Error' : 'Sync OK';
  const dot =
    estado === 'syncing'
      ? 'bg-primary animate-pulse'
      : estado === 'offline'
        ? 'bg-muted-foreground'
        : estado === 'error'
          ? 'bg-destructive'
          : 'bg-foreground';
  return (
    <span className="label-mono inline-flex items-center gap-1.5 border-2 border-foreground bg-card px-2 py-1 text-[10px]">
      <span className={`size-2 border border-foreground ${dot}`} aria-hidden="true" />
      {etiqueta}
    </span>
  );
}
