import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const sendMessage = vi.fn();
const setMessages = vi.fn();
const useChat = vi.fn();
vi.mock('@ai-sdk/react', () => ({ useChat: (opts: unknown) => useChat(opts) }));
vi.mock('ai', () => ({ DefaultChatTransport: class { constructor(public o: unknown) {} } }));

const addMessage = vi.fn().mockResolvedValue(undefined);
const clearThread = vi.fn().mockResolvedValue(undefined);
vi.mock('@/lib/repositories/coach', () => ({ addMessage: (...a: unknown[]) => addMessage(...a), clearThread: (...a: unknown[]) => clearThread(...a) }));

const recogerSnapshot = vi.fn();
vi.mock('@/lib/coach-snapshot', () => ({ recogerSnapshot: (...a: unknown[]) => recogerSnapshot(...a) }));

import { CoachChat } from '@/components/coach-chat';

const SNAP = { estancados: [], semana: { sesiones: 0, objetivo: 3, volumen: 0, deltaPct: null, prs: [] }, grupos: [] };

beforeEach(() => {
  sendMessage.mockReset();
  setMessages.mockReset();
  addMessage.mockReset().mockResolvedValue(undefined);
  clearThread.mockReset().mockResolvedValue(undefined);
  recogerSnapshot.mockReset().mockResolvedValue(SNAP);
  useChat.mockReturnValue({ messages: [], sendMessage, setMessages, status: 'ready', error: undefined });
});

it('renderiza el hilo sembrado', () => {
  useChat.mockReturnValue({
    messages: [
      { id: 'a', role: 'user', parts: [{ type: 'text', text: '¿subo peso?' }] },
      { id: 'b', role: 'assistant', parts: [{ type: 'text', text: 'Sí, +2.5kg' }] },
    ],
    sendMessage, setMessages, status: 'ready', error: undefined,
  });
  render(<CoachChat seed={[]} />);
  expect(screen.getByText('¿subo peso?')).toBeInTheDocument();
  expect(screen.getByText('Sí, +2.5kg')).toBeInTheDocument();
});

it('al enviar: persiste el mensaje del usuario y llama a sendMessage con el snapshot', async () => {
  render(<CoachChat seed={[]} />);
  await userEvent.type(screen.getByPlaceholderText(/pregunta/i), 'hola coach');
  await userEvent.click(screen.getByRole('button', { name: /enviar/i }));
  expect(addMessage).toHaveBeenCalledWith('user', 'hola coach');
  expect(recogerSnapshot).toHaveBeenCalled();
  expect(sendMessage).toHaveBeenCalledTimes(1);
  const [msg, opts] = sendMessage.mock.calls[0];
  expect(msg).toEqual({ text: 'hola coach' });
  expect(opts.body.snapshot).toBeDefined();
});

it('un chip de insight rápido envía su pregunta', async () => {
  render(<CoachChat seed={[]} />);
  await userEvent.click(screen.getByRole('button', { name: 'Analiza mi semana' }));
  expect(sendMessage).toHaveBeenCalledTimes(1);
  expect(sendMessage.mock.calls[0][0]).toEqual({ text: 'Analiza mi semana' });
});

it('muestra un aviso de error cuando status es error', () => {
  useChat.mockReturnValue({ messages: [], sendMessage, setMessages, status: 'error', error: new Error('x') });
  render(<CoachChat seed={[]} />);
  expect(screen.getByText(/no se pudo contactar/i)).toBeInTheDocument();
});

it('muestra el disclaimer', () => {
  render(<CoachChat seed={[]} />);
  expect(screen.getByText(/no consejo médico/i)).toBeInTheDocument();
});

it('el botón Borrar conversación limpia el hilo (Dexie + UI)', async () => {
  useChat.mockReturnValue({
    messages: [{ id: 'a', role: 'user', parts: [{ type: 'text', text: 'hola' }] }],
    sendMessage, setMessages, status: 'ready', error: undefined,
  });
  render(<CoachChat seed={[]} />);
  await userEvent.click(screen.getByRole('button', { name: /borrar conversación/i }));
  expect(clearThread).toHaveBeenCalledTimes(1);
  expect(setMessages).toHaveBeenCalledWith([]);
  expect(clearThread.mock.invocationCallOrder[0]).toBeLessThan(setMessages.mock.invocationCallOrder[0]);
});
