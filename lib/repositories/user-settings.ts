import { db } from '@/lib/db/database';
import type { UserSetting } from '@/lib/db/types';

const now = () => Date.now();

/** Lee un ajuste por su clave (id). undefined si no existe, está borrado o el JSON es inválido. */
export async function getSetting<T>(clave: string): Promise<T | undefined> {
  const row = await db.userSettings.get(clave);
  if (!row || row.deletedAt !== null) return undefined;
  try {
    return JSON.parse(row.valor) as T;
  } catch {
    return undefined;
  }
}

/** Upsert de un ajuste (id = clave). Serializa el valor a JSON y sube updatedAt. */
export async function setSetting<T>(clave: string, valor: T): Promise<void> {
  const existing = await db.userSettings.get(clave);
  const row: UserSetting = {
    id: clave,
    userId: existing?.userId ?? null,
    valor: JSON.stringify(valor),
    updatedAt: now(),
    deletedAt: null,
  };
  await db.userSettings.put(row);
}

/** Borra (tombstone) un ajuste; getSetting volverá a devolver undefined. */
export async function deleteSetting(clave: string): Promise<void> {
  const ts = now();
  await db.userSettings.update(clave, { deletedAt: ts, updatedAt: ts });
}
