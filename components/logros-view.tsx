import type { Achievement } from '@/lib/db/types';
import type { PRItem } from '@/lib/repositories/stats';
import { LOGROS_DEF } from '@/lib/logros';

function fechaCorta(ms: number): string {
  return new Date(ms).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit', year: '2-digit' });
}

export function LogrosView({
  racha,
  achievements,
  prs,
}: {
  racha: { actual: number; mejor: number };
  achievements: Map<string, Achievement>;
  prs: PRItem[];
}) {
  return (
    <div className="space-y-6">
      <section className="brutal-box brutal-shadow space-y-1 bg-primary/10 p-4">
        <p className="label-mono text-[11px] text-muted-foreground">🔥 Racha actual</p>
        <p className="font-[family-name:var(--font-display)] text-5xl leading-none">{racha.actual}</p>
        <p className="label-mono text-[10px] text-muted-foreground">semanas · mejor: {racha.mejor}</p>
      </section>

      <section className="space-y-2">
        <h2 className="label-mono text-[11px] text-muted-foreground">Hitos</h2>
        <div className="grid grid-cols-2 gap-2">
          {LOGROS_DEF.map((def) => {
            const a = achievements.get(def.clave);
            const desbloqueado = Boolean(a);
            return (
              <div
                key={def.clave}
                className={`brutal-box space-y-0.5 p-3 ${desbloqueado ? '' : 'opacity-50'}`}
              >
                <p className="font-bold">{def.titulo}</p>
                <p className="label-mono text-[9px] text-muted-foreground">{def.descripcion}</p>
                {desbloqueado && (
                  <p className="label-mono text-[9px] text-primary">desbloqueado {fechaCorta(a!.fechaDesbloqueo)}</p>
                )}
              </div>
            );
          })}
        </div>
      </section>

      <section className="space-y-2">
        <h2 className="label-mono text-[11px] text-muted-foreground">Récords (PRs)</h2>
        {prs.length === 0 ? (
          <p className="text-muted-foreground">Aún no hay récords. ¡A entrenar!</p>
        ) : (
          <ul className="brutal-box divide-y-2 divide-foreground">
            {prs.map((pr) => (
              <li key={pr.exerciseId} className="flex items-center justify-between gap-2 px-3 py-2">
                <span className="font-semibold">{pr.nombre}</span>
                <span className="label-mono text-[10px] text-muted-foreground tabular-nums">
                  {pr.peso} kg · {fechaCorta(pr.fecha)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
