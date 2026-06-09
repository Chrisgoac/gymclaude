import { db } from '@/lib/db/database';
import type { CoachMessage } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

/** Mensajes activos del hilo, en orden cronológico. */
export async function listMessages(): Promise<CoachMessage[]> {
  const all = activo(await db.coachMessages.toArray());
  return all.sort((a, b) => a.createdAt - b.createdAt);
}

/** Añade un mensaje (rol user|assistant) al hilo. */
export async function addMessage(rol: CoachMessage['rol'], contenido: string): Promise<CoachMessage> {
  const ts = now();
  const msg: CoachMessage = {
    id: crypto.randomUUID(),
    userId: null,
    rol,
    contenido,
    createdAt: ts,
    updatedAt: ts,
    deletedAt: null,
  };
  await db.coachMessages.put(msg);
  return msg;
}

/** Borra (tombstone) todos los mensajes del hilo. */
export async function clearThread(): Promise<void> {
  const ts = now();
  const all = activo(await db.coachMessages.toArray());
  await db.transaction('rw', db.coachMessages, async () => {
    for (const m of all) await db.coachMessages.update(m.id, { deletedAt: ts, updatedAt: ts });
  });
}
