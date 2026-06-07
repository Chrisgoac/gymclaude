'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Dumbbell, ClipboardList, LayoutGrid, TrendingUp, History, type LucideIcon } from 'lucide-react';

const TABS: { href: string; label: string; Icon: LucideIcon }[] = [
  { href: '/', label: 'Entrenar', Icon: Dumbbell },
  { href: '/rutinas', label: 'Rutinas', Icon: ClipboardList },
  { href: '/ejercicios', label: 'Ejercicios', Icon: LayoutGrid },
  { href: '/progreso', label: 'Progreso', Icon: TrendingUp },
  { href: '/historial', label: 'Historial', Icon: History },
];

export function BottomNav() {
  const pathname = usePathname();
  return (
    <nav className="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t-2 border-foreground bg-card pb-[env(safe-area-inset-bottom)]">
      {TABS.map(({ href, label, Icon }) => {
        // La pestaña Entrenar ('/') cubre también las subrutas del registro (/entrenar/...).
        const active =
          href === '/'
            ? pathname === '/' || pathname.startsWith('/entrenar')
            : pathname.startsWith(href);
        return (
          <Link
            key={href}
            href={href}
            className={`label-mono relative flex flex-col items-center gap-1 py-2.5 text-center text-[10px] transition-colors [&:not(:first-child)]:border-l-2 [&:not(:first-child)]:border-foreground ${
              active
                ? 'bg-primary text-primary-foreground font-bold'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {active && (
              <span className="absolute inset-x-0 top-0 h-1 bg-foreground" aria-hidden="true" />
            )}
            <Icon className="size-5" strokeWidth={2} aria-hidden="true" />
            <span className="leading-none">{label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
