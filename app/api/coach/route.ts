import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { streamText, convertToModelMessages, type UIMessage } from 'ai';
import { modeloCoach, deepseekConfigured } from '@/lib/coach-model';
import { systemPrompt } from '@/lib/coach-prompt';
import type { CoachSnapshot } from '@/lib/coach-snapshot';

export const maxDuration = 60;

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  if (!deepseekConfigured()) return new NextResponse('Coach no configurado', { status: 503 });

  let body: { messages?: UIMessage[]; snapshot?: CoachSnapshot };
  try {
    body = await req.json();
  } catch {
    return new NextResponse('Petición inválida', { status: 400 });
  }
  const { messages, snapshot } = body;
  if (!Array.isArray(messages)) return new NextResponse('Petición inválida', { status: 400 });

  const result = streamText({
    model: modeloCoach(),
    system: systemPrompt(snapshot ?? ({} as CoachSnapshot)),
    messages: await convertToModelMessages(messages),
  });
  return result.toUIMessageStreamResponse();
}
