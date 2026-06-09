import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { streamText, convertToModelMessages, type UIMessage } from 'ai';
import { modeloCoach, deepseekConfigured } from '@/lib/coach-model';
import { systemPrompt } from '@/lib/coach-prompt';
import type { CoachSnapshot } from '@/lib/coach-snapshot';

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  if (!deepseekConfigured()) return new NextResponse('Coach no configurado', { status: 503 });

  const { messages, snapshot } = (await req.json()) as { messages: UIMessage[]; snapshot: CoachSnapshot };

  const result = streamText({
    model: modeloCoach(),
    system: systemPrompt(snapshot),
    messages: await convertToModelMessages(messages),
  });
  return result.toUIMessageStreamResponse();
}
