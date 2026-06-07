'use client';

import { PERIODOS, type Periodo } from '@/lib/period';

export function PeriodSelector({ value, onChange }: { value: Periodo; onChange: (p: Periodo) => void }) {
  return (
    <div className="flex border-2 border-foreground">
      {PERIODOS.map(({ id, label }, i) => (
        <button
          key={id}
          onClick={() => onChange(id)}
          className={`label-mono flex-1 py-1.5 text-center text-[10px] transition-colors ${
            i > 0 ? 'border-l-2 border-foreground' : ''
          } ${value === id ? 'bg-primary text-primary-foreground font-bold' : 'bg-card text-muted-foreground'}`}
        >
          {label}
        </button>
      ))}
    </div>
  );
}
