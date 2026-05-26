'use client';

import { useRef, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { getPhoto, setPhoto, removePhoto } from '@/lib/repositories/exercise-photos';
import { compressImage } from '@/lib/image/compress';

export function ExercisePhotoPicker({ exerciseId }: { exerciseId: string }) {
  const foto = useLiveQuery(() => getPhoto(exerciseId), [exerciseId]);
  const inputRef = useRef<HTMLInputElement>(null);
  const [estado, setEstado] = useState('');

  async function onFile(file: File) {
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      setEstado('Necesitas conexión para subir fotos.');
      return;
    }
    setEstado('Subiendo…');
    try {
      const blob = await compressImage(file);
      const fd = new FormData();
      fd.append('exerciseId', exerciseId);
      fd.append('file', blob, 'foto.jpg');
      const res = await fetch('/api/exercise-photos', { method: 'POST', body: fd });
      if (!res.ok) throw new Error(String(res.status));
      const { url, key } = (await res.json()) as { url: string; key: string };
      const prevKey = await setPhoto(exerciseId, { url, key });
      if (prevKey) void borrarEnR2(prevKey);
      setEstado('');
    } catch {
      setEstado('No se pudo subir la foto.');
    }
  }

  async function quitar() {
    const key = await removePhoto(exerciseId);
    if (key) void borrarEnR2(key);
  }

  return (
    <div className="space-y-2">
      {foto ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={foto.url} alt="Foto del ejercicio" className="h-40 w-full border-2 border-foreground object-cover" />
      ) : (
        <div className="grid h-40 w-full place-items-center border-2 border-dashed border-foreground bg-card/50">
          <span className="label-mono text-[11px] text-muted-foreground">Sin foto</span>
        </div>
      )}
      <div className="flex items-center gap-3">
        <button
          className="label-mono border-2 border-foreground bg-secondary px-3 py-2 text-[11px] text-secondary-foreground brutal-shadow-sm transition-transform active:translate-x-[2px] active:translate-y-[2px]"
          onClick={() => inputRef.current?.click()}
        >
          {foto ? 'Cambiar foto' : 'Añadir foto'}
        </button>
        {foto && (
          <button className="label-mono text-[10px] text-muted-foreground hover:text-destructive" onClick={quitar}>
            Quitar
          </button>
        )}
        {estado && <span className="label-mono text-[10px] text-muted-foreground">{estado}</span>}
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) void onFile(f);
          e.target.value = '';
        }}
      />
    </div>
  );
}

async function borrarEnR2(key: string): Promise<void> {
  try {
    await fetch('/api/exercise-photos', {
      method: 'DELETE',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ key }),
    });
  } catch {
    // best-effort
  }
}
