import { describe, it, expect, beforeEach } from 'vitest';
import 'fake-indexeddb/auto';
import { db } from '@/lib/db/database';
import { listMessages, addMessage, clearThread } from '@/lib/repositories/coach';

beforeEach(async () => {
  await db.coachMessages.clear();
});

describe('coach repo', () => {
  it('addMessage + listMessages devuelve el hilo en orden cronológico', async () => {
    await addMessage('user', 'hola');
    await addMessage('assistant', 'qué tal');
    const hilo = await listMessages();
    expect(hilo.map((m) => [m.rol, m.contenido])).toEqual([
      ['user', 'hola'],
      ['assistant', 'qué tal'],
    ]);
    expect(hilo[0].createdAt).toBeLessThanOrEqual(hilo[1].createdAt);
  });

  it('addMessage genera id y marca sync', async () => {
    const m = await addMessage('user', 'x');
    expect(m.id).toBeTruthy();
    expect(m.userId).toBeNull();
    expect(m.deletedAt).toBeNull();
    expect(m.updatedAt).toBeGreaterThan(0);
  });

  it('clearThread hace tombstone de todos (listMessages vacío, filas siguen)', async () => {
    await addMessage('user', 'a');
    await addMessage('assistant', 'b');
    await clearThread();
    expect(await listMessages()).toHaveLength(0);
    expect(await db.coachMessages.count()).toBe(2);
  });
});
