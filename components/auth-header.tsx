'use client';

import { SignInButton, Show, UserButton } from '@clerk/nextjs';
import { SyncProvider } from '@/components/sync-provider';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Settings, Sparkles } from 'lucide-react';

export function AuthHeader() {
  const pathname = usePathname();
  const ajustesActivo = pathname.startsWith('/ajustes');
  const coachActivo = pathname.startsWith('/coach');

  return (
    <header className="sticky top-0 z-30 border-b-2 border-foreground bg-card">
      {/* Franja de peligro: el detalle que ancla la estética de hierro. */}
      <div className="hazard h-1.5" aria-hidden="true" />
      <div className="mx-auto flex max-w-md items-center justify-between px-4 py-2.5">
        <div className="flex items-center gap-2.5">
          <span className="grid size-7 place-items-center border-2 border-foreground bg-primary font-[family-name:var(--font-display)] text-base leading-none text-primary-foreground">
            G
          </span>
          <span className="font-[family-name:var(--font-display)] text-2xl uppercase leading-none tracking-tight">
            Gym<span className="text-primary">Log</span>
          </span>
        </div>
        <div className="flex items-center gap-3">
          <Link
            href="/coach"
            aria-label="Coach IA"
            aria-current={coachActivo ? 'page' : undefined}
            className={`grid size-8 place-items-center border-2 border-foreground transition-transform active:translate-x-[1px] active:translate-y-[1px] ${
              coachActivo ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground'
            }`}
          >
            <Sparkles className="size-4" strokeWidth={2} aria-hidden="true" />
          </Link>
          <Link
            href="/ajustes"
            aria-label="Ajustes"
            aria-current={ajustesActivo ? 'page' : undefined}
            className={`grid size-8 place-items-center border-2 border-foreground transition-transform active:translate-x-[1px] active:translate-y-[1px] ${
              ajustesActivo ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground'
            }`}
          >
            <Settings className="size-4" strokeWidth={2} aria-hidden="true" />
          </Link>
          <SyncProvider />
          <Show when="signed-out">
            <SignInButton mode="modal">
              <button className="label-mono border-2 border-foreground bg-secondary px-2.5 py-1.5 text-[10px] text-secondary-foreground brutal-shadow-sm transition-transform active:translate-x-[2px] active:translate-y-[2px] active:shadow-none">
                Entrar
              </button>
            </SignInButton>
          </Show>
          <Show when="signed-in">
            <UserButton />
          </Show>
        </div>
      </div>
    </header>
  );
}
