'use client';

import { useRef, useState } from 'react';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { compressImage } from '@/lib/image/compress';
import { addPhoto } from '@/lib/repositories/progress-photos';
import { ANGULOS, anguloLabel } from '@/lib/progress-photos';
import type { AnguloFoto } from '@/lib/db/types';

function hoyISO(): string {
  const d = new Date();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

export function ProgressPhotoUpload() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [angulo, setAngulo] = useState<AnguloFoto>('frente');
  const [fecha, setFecha] = useState(hoyISO());
  const [nota, setNota] = useState('');
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
      fd.append('file', blob, 'foto.jpg');
      const res = await fetch('/api/progress-photos', { method: 'POST', body: fd });
      if (!res.ok) throw new Error(String(res.status));
      const { url, key } = (await res.json()) as { url: string; key: string };
      const fechaMs = new Date(`${fecha}T00:00:00`).getTime();
      await addPhoto({ url, key, fecha: fechaMs, angulo, nota: nota.trim() || null });
      setNota('');
      setEstado('');
    } catch {
      setEstado('No se pudo subir la foto.');
    }
  }

  return (
    <div className="brutal-box space-y-3 p-3">
      <p className="label-mono text-[10px] text-muted-foreground">Nueva foto de progreso</p>
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1">
          <Label htmlFor="angulo">Ángulo</Label>
          <select
            id="angulo"
            className="w-full border-2 border-foreground bg-card p-2"
            value={angulo}
            onChange={(e) => setAngulo(e.target.value as AnguloFoto)}
          >
            {ANGULOS.map((a) => (
              <option key={a} value={a}>
                {anguloLabel[a]}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-1">
          <Label htmlFor="fecha-foto">Fecha</Label>
          <input
            id="fecha-foto"
            type="date"
            className="h-9 w-full border-2 border-foreground bg-card px-2"
            value={fecha}
            onChange={(e) => setFecha(e.target.value)}
          />
        </div>
      </div>
      <div className="space-y-1">
        <Label htmlFor="nota-foto">Nota</Label>
        <Input id="nota-foto" value={nota} onChange={(e) => setNota(e.target.value)} placeholder="Opcional" />
      </div>
      <div className="flex items-center gap-3">
        <button
          type="button"
          className="label-mono border-2 border-foreground bg-secondary px-3 py-2 text-[11px] text-secondary-foreground brutal-shadow-sm transition-transform active:translate-x-[2px] active:translate-y-[2px]"
          onClick={() => inputRef.current?.click()}
        >
          Añadir foto
        </button>
        {estado && <span className="label-mono text-[10px] text-muted-foreground">{estado}</span>}
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        capture="environment"
        aria-label="Foto"
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
