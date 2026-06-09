import Link from 'next/link';
import { Sparkles } from 'lucide-react';
import { StartWorkout } from '@/components/start-workout';
import { WeeklyDigestMini } from '@/components/weekly-digest-mini';

export default function EntrenarPage() {
  return (
    <div className="space-y-6">
      <div>
        <p className="label-mono text-[11px] text-muted-foreground">Listo para mover hierro</p>
        <h1 className="text-5xl">Entrenar</h1>
      </div>
      <WeeklyDigestMini />
      <Link
        href="/coach"
        aria-label="Pregunta al coach"
        className="brutal-box flex items-center justify-between gap-3 px-3 py-2.5 transition-transform active:translate-x-[2px] active:translate-y-[2px]"
      >
        <span className="flex items-center gap-2">
          <Sparkles className="size-4 text-primary" strokeWidth={2} aria-hidden="true" />
          <span className="font-semibold">Pregunta al coach</span>
        </span>
        <span className="label-mono text-[10px] text-muted-foreground">IA</span>
      </Link>
      <StartWorkout />
    </div>
  );
}
