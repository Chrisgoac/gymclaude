'use client';

import { useEffect } from 'react';
import { seedCatalogIfEmpty } from '@/lib/db/seed';

export function DbProvider({ children }: { children: React.ReactNode }) {
  useEffect(() => {
    void seedCatalogIfEmpty();
  }, []);
  return <>{children}</>;
}
