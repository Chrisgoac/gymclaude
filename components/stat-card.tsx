export function StatCard({ valor, unidad, destacado = false }: { valor: string; unidad: string; destacado?: boolean }) {
  return (
    <div className="border-2 border-foreground bg-card p-2.5 brutal-shadow-sm">
      <p className={`font-[family-name:var(--font-display)] text-3xl leading-none ${destacado ? 'text-primary' : ''}`}>
        {valor}
      </p>
      <p className="label-mono mt-1 text-[9px] text-muted-foreground">{unidad}</p>
    </div>
  );
}
