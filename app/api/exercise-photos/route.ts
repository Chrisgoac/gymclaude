import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { r2Configured, putImage, deleteR2Object, publicUrl } from '@/lib/r2/client';

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  if (!r2Configured()) return new NextResponse('R2 no configurado', { status: 503 });

  const form = await req.formData();
  const exerciseId = String(form.get('exerciseId') ?? '');
  const file = form.get('file');
  if (!exerciseId || !(file instanceof Blob)) {
    return new NextResponse('Petición inválida', { status: 400 });
  }
  const key = `${userId}/${exerciseId}/${crypto.randomUUID()}.jpg`;
  const bytes = new Uint8Array(await file.arrayBuffer());
  await putImage(key, bytes, 'image/jpeg');
  return NextResponse.json({ url: publicUrl(key), key });
}

export async function DELETE(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  if (!r2Configured()) return new NextResponse('R2 no configurado', { status: 503 });

  const { key } = (await req.json()) as { key?: string };
  if (key) {
    try {
      await deleteR2Object(key);
    } catch {
      // best-effort: ignorar si el objeto no existe
    }
  }
  return NextResponse.json({ ok: true });
}
