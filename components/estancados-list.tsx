import type { Estancado } from '@/lib/repositories/stats';
import { formatHaceDias } from '@/lib/fecha';

export function EstancadosList({ data }: { data: Estancado[] }) {
  if (data.length === 0) {
    return <p className="text-muted-foreground">Ningún ejercicio estancado ahora mismo.</p>;
  }
  return (
    <ul className="brutal-box divide-y-2 divide-foreground">
      {data.map((e) => (
        <li key={e.exerciseId} className="flex items-center justify-between gap-2 px-3 py-2">
          <span className="font-medium">{e.nombre}</span>
          <span className="label-mono text-right text-[10px] text-destructive">
            {e.sesionesSinMejora} {e.sesionesSinMejora === 1 ? 'sesión' : 'sesiones'} sin mejorar
            {e.ultimaMejoraFecha != null && (
              <span className="block text-muted-foreground">última mejora {formatHaceDias(e.ultimaMejoraFecha)}</span>
            )}
          </span>
        </li>
      ))}
    </ul>
  );
}
