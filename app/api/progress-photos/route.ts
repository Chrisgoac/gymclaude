import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { legacyDeletingResponse } from '@/lib/account/access';
import { getPoolDb } from '@/db/pool';
import { enqueueMediaDeletion, isOwnedV1Key } from '@/lib/api/v1/media/deletions';
import { r2Configured, putImage, deleteR2Object, publicUrl } from '@/lib/r2/client';

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  const deleting = await legacyDeletingResponse(userId);
  if (deleting) return deleting;
  if (!r2Configured()) return new NextResponse('R2 no configurado', { status: 503 });

  const form = await req.formData();
  const file = form.get('file');
  if (!(file instanceof Blob)) {
    return new NextResponse('Petición inválida', { status: 400 });
  }
  // La ruta es la frontera de confianza: validar tipo y tamaño aunque el cliente ya comprima.
  if (file.type && !file.type.startsWith('image/')) {
    return new NextResponse('Tipo no permitido', { status: 400 });
  }
  if (file.size > 8 * 1024 * 1024) {
    return new NextResponse('Imagen demasiado grande', { status: 413 });
  }
  const key = `${userId}/progress/${crypto.randomUUID()}.jpg`;
  const bytes = new Uint8Array(await file.arrayBuffer());
  await putImage(key, bytes, 'image/jpeg');
  return NextResponse.json({ url: publicUrl(key), key });
}

export async function DELETE(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  const deleting = await legacyDeletingResponse(userId);
  if (deleting) return deleting;

  const { key } = (await req.json()) as { key?: string };
  // Foto subida desde Android (bucket privado v1): la PWA no la borra directamente; se encola el
  // borrado diferido, que se cancela solo si la fila sigue viva. No necesita el bucket público.
  if (key && isOwnedV1Key(userId, key, 'progress')) {
    await enqueueMediaDeletion(getPoolDb(), { userId, objectKey: key, at: Date.now() });
    return NextResponse.json({ ok: true });
  }
  if (!r2Configured()) return new NextResponse('R2 no configurado', { status: 503 });
  // El key está namespaced por userId; no permitir borrar objetos de otros usuarios.
  if (key && !key.startsWith(`${userId}/`)) {
    return new NextResponse('Forbidden', { status: 403 });
  }
  if (key) {
    try {
      await deleteR2Object(key);
    } catch {
      // best-effort: ignorar si el objeto no existe
    }
  }
  return NextResponse.json({ ok: true });
}
