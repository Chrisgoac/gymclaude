'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';

const TABS = [
  { href: '/', label: 'Entrenar' },
  { href: '/rutinas', label: 'Rutinas' },
  { href: '/progreso', label: 'Progreso' },
  { href: '/historial', label: 'Historial' },
] as const;

export function BottomNav() {
  const pathname = usePathname();
  return (
    <nav className="fixed inset-x-0 bottom-0 z-10 grid grid-cols-4 border-t bg-background">
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
            className={`py-3 text-center text-sm ${active ? 'font-semibold text-primary' : 'text-muted-foreground'}`}
          >
            {tab.label}
          </Link>
        );
      })}
    </nav>
  );
}
