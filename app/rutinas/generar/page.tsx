import { MesoGenerator } from '@/components/meso-generator';

export default function GenerarRutinaPage() {
  return (
    <div className="space-y-4">
      <div>
        <p className="label-mono text-[11px] text-muted-foreground">Coach IA</p>
        <h1 className="text-2xl font-bold">Generar mesociclo</h1>
      </div>
      <MesoGenerator />
    </div>
  );
}
