import type { Estancado } from '@/lib/repositories/stats';

export function EstancadosList({ data }: { data: Estancado[] }) {
  if (data.length === 0) {
    return <p className="text-muted-foreground">Ningún ejercicio estancado ahora mismo.</p>;
  }
  return (
    <ul className="brutal-box divide-y-2 divide-foreground">
      {data.map((e) => (
        <li key={e.exerciseId} className="flex items-center justify-between gap-2 px-3 py-2">
          <span className="font-medium">{e.nombre}</span>
          <span className="label-mono text-[10px] text-destructive">
            {e.sesionesSinMejora} sesiones sin mejorar
          </span>
        </li>
      ))}
    </ul>
  );
}
