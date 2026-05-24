'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';

const TABS = [
  { href: '/', label: 'Entrenar' },
  { href: '/rutinas', label: 'Rutinas' },
  { href: '/ejercicios', label: 'Ejercicios' },
  { href: '/progreso', label: 'Progreso' },
  { href: '/historial', label: 'Historial' },
  { href: '/ajustes', label: 'Ajustes' },
] as const;

export function BottomNav() {
  const pathname = usePathname();
  return (
    <nav className="fixed inset-x-0 bottom-0 z-40 grid grid-cols-6 border-t-2 border-foreground bg-card">
      {TABS.map((tab) => {
        // La pestaña Entrenar ('/') cubre también las subrutas del registro (/entrenar/...).
        const active =
          tab.href === '/'
            ? pathname === '/' || pathname.startsWith('/entrenar')
            : pathname.startsWith(tab.href);
        return (
          <Link
            key={tab.href}
            href={tab.href}
            className={`label-mono relative py-3.5 text-center text-[10px] transition-colors [&:not(:first-child)]:border-l-2 [&:not(:first-child)]:border-foreground ${
              active
                ? 'bg-primary text-primary-foreground font-bold'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {active && (
              <span className="absolute inset-x-0 top-0 h-1 bg-foreground" aria-hidden="true" />
            )}
            {tab.label}
          </Link>
        );
      })}
    </nav>
  );
}
