import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { generateObject, jsonSchema } from 'ai';
import { modeloCoach, deepseekConfigured } from '@/lib/coach-model';
import { promptMesociclo, MESO_SCHEMA, type MesoParams } from '@/lib/meso-prompt';
import type { JSONSchema7 } from '@ai-sdk/provider';
import type { CoachSnapshot } from '@/lib/coach-snapshot';

export const maxDuration = 120;

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  if (!deepseekConfigured()) return new NextResponse('Coach no disponible', { status: 503 });

  let payload: { params: MesoParams; snapshot: CoachSnapshot; catalogo: { nombre: string; grupo: string; equipamiento: string }[] };
  try {
    payload = await req.json();
  } catch {
    return new NextResponse('Petición inválida', { status: 400 });
  }
  if (!payload?.params || !payload?.snapshot) {
    return new NextResponse('Petición inválida', { status: 400 });
  }

  const prompt = promptMesociclo(payload.params, payload.snapshot, payload.catalogo ?? []);
  try {
    const { object } = await generateObject({
      model: modeloCoach(),
      schema: jsonSchema(MESO_SCHEMA as unknown as JSONSchema7),
      prompt,
    });
    return NextResponse.json(object);
  } catch {
    return new NextResponse('No se pudo generar el mesociclo', { status: 502 });
  }
}
