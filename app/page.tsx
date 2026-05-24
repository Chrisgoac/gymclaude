import { StartWorkout } from '@/components/start-workout';

export default function EntrenarPage() {
  return (
    <div className="space-y-6">
      <div>
        <p className="label-mono text-[11px] text-muted-foreground">Listo para mover hierro</p>
        <h1 className="text-5xl">Entrenar</h1>
      </div>
      <StartWorkout />
    </div>
  );
}
