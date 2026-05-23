'use client';

import { useState } from 'react';
import type { Exercise, MuscleGroup, Equipment, ExerciseType } from '@/lib/db/types';
import { MUSCLE_GROUPS, EQUIPMENTS, EXERCISE_TYPES } from '@/lib/db/types';
import { muscleGroupLabel, equipmentLabel, exerciseTypeLabel } from '@/lib/labels';
import { createExercise, updateExercise } from '@/lib/repositories/exercises';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';

export function ExerciseForm({
  existing,
  onSaved,
}: {
  existing?: Exercise;
  onSaved: () => void;
}) {
  const [nombre, setNombre] = useState(existing?.nombre ?? '');
  const [grupoMuscular, setGrupo] = useState<MuscleGroup>(existing?.grupoMuscular ?? 'pecho');
  const [equipamiento, setEquip] = useState<Equipment>(existing?.equipamiento ?? 'barra');
  const [tipo, setTipo] = useState<ExerciseType>(existing?.tipo ?? 'compuesto');
  const [videoUrl, setVideoUrl] = useState(existing?.videoUrl ?? '');
  const [notas, setNotas] = useState(existing?.notas ?? '');
  const [error, setError] = useState('');

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (nombre.trim() === '') {
      setError('El nombre es obligatorio');
      return;
    }
    const data = {
      nombre: nombre.trim(),
      grupoMuscular,
      equipamiento,
      tipo,
      videoUrl: videoUrl.trim() || undefined,
      notas: notas.trim() || undefined,
    };
    if (existing) {
      await updateExercise(existing.id, data);
    } else {
      await createExercise(data);
    }
    onSaved();
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="space-y-1">
        <Label htmlFor="nombre">Nombre</Label>
        <Input id="nombre" value={nombre} onChange={(e) => setNombre(e.target.value)} />
      </div>
      <div className="space-y-1">
        <Label htmlFor="grupo">Grupo muscular</Label>
        <select id="grupo" className="w-full rounded-md border p-2" value={grupoMuscular} onChange={(e) => setGrupo(e.target.value as MuscleGroup)}>
          {MUSCLE_GROUPS.map((g) => (<option key={g} value={g}>{muscleGroupLabel[g]}</option>))}
        </select>
      </div>
      <div className="space-y-1">
        <Label htmlFor="equip">Equipamiento</Label>
        <select id="equip" className="w-full rounded-md border p-2" value={equipamiento} onChange={(e) => setEquip(e.target.value as Equipment)}>
          {EQUIPMENTS.map((eq) => (<option key={eq} value={eq}>{equipmentLabel[eq]}</option>))}
        </select>
      </div>
      <div className="space-y-1">
        <Label htmlFor="tipo">Tipo</Label>
        <select id="tipo" className="w-full rounded-md border p-2" value={tipo} onChange={(e) => setTipo(e.target.value as ExerciseType)}>
          {EXERCISE_TYPES.map((t) => (<option key={t} value={t}>{exerciseTypeLabel[t]}</option>))}
        </select>
      </div>
      <div className="space-y-1">
        <Label htmlFor="video">Vídeo (opcional)</Label>
        <Input id="video" value={videoUrl} onChange={(e) => setVideoUrl(e.target.value)} />
      </div>
      <div className="space-y-1">
        <Label htmlFor="notas">Notas (opcional)</Label>
        <Textarea id="notas" value={notas} onChange={(e) => setNotas(e.target.value)} />
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <Button type="submit">Guardar</Button>
    </form>
  );
}
