'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { createRoutine } from '@/lib/repositories/routines';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';

export default function NuevaRutinaPage() {
  const router = useRouter();
  const [nombre, setNombre] = useState('');
  const [descripcion, setDescripcion] = useState('');
  const [error, setError] = useState('');

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (nombre.trim() === '') {
      setError('El nombre es obligatorio');
      return;
    }
    const r = await createRoutine({ nombre: nombre.trim(), descripcion: descripcion.trim() || undefined });
    router.push(`/rutinas/${r.id}`);
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Nueva rutina</h1>
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-1">
          <Label htmlFor="nombre">Nombre</Label>
          <Input id="nombre" value={nombre} onChange={(e) => setNombre(e.target.value)} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="descripcion">Descripción (opcional)</Label>
          <Textarea id="descripcion" value={descripcion} onChange={(e) => setDescripcion(e.target.value)} />
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
        <Button type="submit">Crear</Button>
      </form>
    </div>
  );
}
