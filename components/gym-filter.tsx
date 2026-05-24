'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { listGyms } from '@/lib/repositories/gyms';
import { useGymFilter } from '@/lib/gym-filter';

export function GymFilter() {
  const gyms = useLiveQuery(() => listGyms(), []);
  const [filtro, setFiltro] = useGymFilter();
  if ((gyms ?? []).length === 0) return null; // sin gimnasios, sin filtro

  const opciones = [{ id: 'all', nombre: 'Todos' }, ...(gyms ?? [])];
  return (
    <div className="flex flex-wrap gap-2">
      {opciones.map((o) => {
        const activo = filtro === o.id;
        return (
          <button
            key={o.id}
            onClick={() => setFiltro(o.id)}
            className={`label-mono border-2 border-foreground px-2.5 py-1.5 text-[10px] transition-transform active:translate-x-[1px] active:translate-y-[1px] ${
              activo ? 'bg-primary text-primary-foreground brutal-shadow-sm' : 'bg-card text-muted-foreground'
            }`}
          >
            {o.nombre}
          </button>
        );
      })}
    </div>
  );
}
