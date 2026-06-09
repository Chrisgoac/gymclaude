'use client';

import { useState } from 'react';
import { useChat } from '@ai-sdk/react';
import { DefaultChatTransport } from 'ai';
import type { CoachMessage } from '@/lib/db/types';
import { addMessage } from '@/lib/repositories/coach';
import { recogerSnapshot } from '@/lib/coach-snapshot';
import { useGymFilter, filtroAGymId } from '@/lib/gym-filter';
import { DISCLAIMER } from '@/lib/coach-prompt';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

const INSIGHTS = ['Analiza mi semana', '¿Por qué estoy estancado?', '¿Qué ejercicio sustituyo?'];

function textoDe(m: { parts?: { type: string; text?: string }[] }): string {
  return (m.parts ?? []).filter((p) => p.type === 'text').map((p) => p.text ?? '').join('');
}

export function CoachChat({ seed }: { seed: CoachMessage[] }) {
  const [filtro] = useGymFilter();
  const gymId = filtroAGymId(filtro);
  const [texto, setTexto] = useState('');

  const { messages, sendMessage, status } = useChat({
    transport: new DefaultChatTransport({ api: '/api/coach' }),
    messages: seed.map((m) => ({ id: m.id, role: m.rol, parts: [{ type: 'text' as const, text: m.contenido }] })),
    onFinish: ({ message, isAbort, isError, isDisconnect }) => {
      if (isAbort || isError || isDisconnect) return;
      const t = textoDe(message);
      if (t) void addMessage('assistant', t);
    },
  });

  const ocupado = status === 'submitted' || status === 'streaming';

  async function enviar(pregunta: string) {
    const q = pregunta.trim();
    if (!q || ocupado) return;
    setTexto('');
    await addMessage('user', q);
    try {
      const snapshot = await recogerSnapshot(gymId);
      await sendMessage({ text: q }, { body: { snapshot } });
    } catch {
      setTexto(q); // restaura para reintentar; el status de useChat ya refleja el error de red del propio send
    }
  }

  return (
    <div className="space-y-4">
      <div className="space-y-3">
        {messages.map((m) => (
          <div key={m.id} className={m.role === 'user' ? 'text-right' : ''}>
            <span className={`inline-block whitespace-pre-wrap brutal-box px-3 py-2 text-sm ${m.role === 'user' ? 'bg-primary text-primary-foreground' : 'bg-card'}`}>
              {textoDe(m)}
            </span>
          </div>
        ))}
        {ocupado && <p className="label-mono text-[10px] text-muted-foreground">El coach está pensando…</p>}
        {status === 'error' && (
          <p className="label-mono text-[10px] text-destructive">No se pudo contactar al coach. Reintenta.</p>
        )}
      </div>

      <div className="flex flex-wrap gap-2">
        {INSIGHTS.map((q) => (
          <button key={q} type="button" onClick={() => void enviar(q)} disabled={ocupado}
            className="label-mono border-2 border-foreground bg-card px-2 py-1 text-[10px] disabled:opacity-50">
            {q}
          </button>
        ))}
      </div>

      <form className="flex items-end gap-2" onSubmit={(e) => { e.preventDefault(); void enviar(texto); }}>
        <Input aria-label="Pregunta a tu coach" placeholder="Pregunta a tu coach…" value={texto} onChange={(e) => setTexto(e.target.value)} className="flex-1" />
        <Button type="submit" disabled={ocupado || texto.trim() === ''}>Enviar</Button>
      </form>

      <p className="label-mono text-[10px] text-muted-foreground">{DISCLAIMER}</p>
    </div>
  );
}
