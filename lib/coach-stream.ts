import { MAX_STREAM_DELTA_CHARS, type AiStreamEvent, type CoachRequest } from '@/lib/api/v1/ai/contracts';
import type { CoachContextTurn, CoachResponseClaim } from '@/lib/api/v1/ai/store';
import type { CoachModelMessage, CoachStreamProvider } from '@/lib/coach-model';
import { coachSystemPrompt } from '@/lib/coach-prompt';

// One Coach turn of POST /api/v1/ai/coach as a newline-delimited JSON stream (application/x-ndjson):
//   start{clientMessageId, responseId} → delta{text}* → complete{responseId, disclaimer: true}
//                                                     | error{code, retryable}
// The response row (ai_responses) is the only thing the server writes: the user's message lives in
// the client's synced coach_messages, so a retry never duplicates it. A retry of the same
// clientMessageId replays a complete response without calling the model, or regenerates an
// incomplete one under the same responseId.

/** Newest user/assistant turns sent as context. */
export const COACH_CONTEXT_TURNS = 20;
/** Char budget of that context. */
export const COACH_CONTEXT_CHARS = 16_000;
export const COACH_MAX_OUTPUT_TOKENS = 1500;
/** The model call is aborted after this long (route maxDuration is 60 s). */
export const COACH_TIMEOUT_MS = 45_000;

export const NDJSON_CONTENT_TYPE = 'application/x-ndjson; charset=utf-8';

/** Persistence the Coach turn needs (lib/api/v1/ai/store.ts in production, in-memory in tests). */
export interface CoachResponseStore {
  claim(userId: string, clientMessageId: string, newId: string, now: number): Promise<CoachResponseClaim>;
  loadContext(userId: string, limitTurns: number, maxChars: number, excludeId: string): Promise<CoachContextTurn[]>;
  /** false when the row was no longer incomplete (a concurrent attempt completed first). */
  complete(userId: string, responseId: string, content: string, now: number): Promise<boolean>;
}

export interface CoachTurnDeps {
  store: CoachResponseStore;
  provider: CoachStreamProvider;
  now?: () => number;
  newId?: () => string;
  timeoutMs?: number;
}

/** Stream error codes (all retryable: the client retries with the same clientMessageId). */
export type CoachStreamErrorCode =
  | 'AI_TIMEOUT'
  | 'AI_PROVIDER_ERROR'
  | 'AI_EMPTY_RESPONSE'
  | 'AI_RESPONSE_CONFLICT'
  | 'INTERNAL_ERROR';

const encoder = new TextEncoder();
const encodeEvent = (event: AiStreamEvent) => encoder.encode(`${JSON.stringify(event)}\n`);

/** Splits text into ≤ MAX_STREAM_DELTA_CHARS pieces without breaking a surrogate pair. */
export function splitDelta(text: string, max = MAX_STREAM_DELTA_CHARS): string[] {
  const pieces: string[] = [];
  let i = 0;
  while (i < text.length) {
    let end = Math.min(i + max, text.length);
    const last = text.charCodeAt(end - 1);
    if (end < text.length && last >= 0xd800 && last <= 0xdbff && end - 1 > i) end--;
    pieces.push(text.slice(i, end));
    i = end;
  }
  return pieces;
}

// Ids and codes only: never prompt, user text, snapshot or model output.
const logOutcome = (responseId: string, outcome: string, extra?: string) =>
  console.warn(`[api/v1/ai/coach] responseId=${responseId} outcome=${outcome}${extra ? ` ${extra}` : ''}`);

/**
 * Claims the response row and loads context (errors here surface as ordinary API errors, before
 * any byte is streamed), then returns the event stream. `clientSignal` is the request's signal:
 * a disconnect stops the model and leaves the row incomplete.
 */
export async function startCoachTurn(
  deps: CoachTurnDeps,
  userId: string,
  request: CoachRequest,
  clientSignal: AbortSignal,
): Promise<ReadableStream<Uint8Array>> {
  const now = deps.now ?? Date.now;
  const claim = await deps.store.claim(userId, request.clientMessageId, (deps.newId ?? (() => crypto.randomUUID()))(), now());
  const start: AiStreamEvent = { type: 'start', clientMessageId: request.clientMessageId, responseId: claim.responseId };

  if (claim.status === 'complete') {
    logOutcome(claim.responseId, 'replay');
    return new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encodeEvent(start));
        for (const text of splitDelta(claim.content)) controller.enqueue(encodeEvent({ type: 'delta', text }));
        controller.enqueue(encodeEvent({ type: 'complete', responseId: claim.responseId, disclaimer: true }));
        controller.close();
      },
    });
  }

  const responseId = claim.responseId;
  const context = await deps.store.loadContext(userId, COACH_CONTEXT_TURNS, COACH_CONTEXT_CHARS, request.clientMessageId);
  const messages: CoachModelMessage[] = [
    ...context.map((t) => ({ role: t.role, content: t.text })),
    { role: 'user', content: request.text },
  ];

  const abort = new AbortController();
  let reason: 'client' | 'timeout' | null = null;
  const stop = (why: 'client' | 'timeout') => {
    if (reason) return;
    reason = why;
    abort.abort(new Error(why === 'timeout' ? 'Coach model timeout' : 'Coach client disconnected'));
  };
  const onClientAbort = () => stop('client');
  let closed = false;

  return new ReadableStream<Uint8Array>({
    start(controller) {
      const emit = (event: AiStreamEvent) => {
        if (!closed) controller.enqueue(encodeEvent(event));
      };
      const finish = () => {
        if (closed) return;
        closed = true;
        controller.close();
      };
      const fail = (code: CoachStreamErrorCode, extra?: string) => {
        logOutcome(responseId, code, extra);
        emit({ type: 'error', code, retryable: true });
        finish();
      };

      void (async () => {
        if (clientSignal.aborted) stop('client');
        clientSignal.addEventListener('abort', onClientAbort, { once: true });
        const timer = setTimeout(() => stop('timeout'), deps.timeoutMs ?? COACH_TIMEOUT_MS);
        const aborted = new Promise<'aborted'>((resolve) => {
          if (abort.signal.aborted) resolve('aborted');
          else abort.signal.addEventListener('abort', () => resolve('aborted'), { once: true });
        });

        emit(start);
        let text = '';
        let providerError: unknown = null;
        let iterator: AsyncIterator<string> | undefined;
        try {
          iterator = deps.provider
            .stream({
              system: coachSystemPrompt(request.snapshot),
              messages,
              maxOutputTokens: COACH_MAX_OUTPUT_TOKENS,
              abortSignal: abort.signal,
            })
            [Symbol.asyncIterator]();
          for (;;) {
            // Race against the abort so a provider that ignores its signal still cannot hang the turn.
            const next = await Promise.race([iterator.next(), aborted]);
            if (next === 'aborted' || next.done) break;
            text += next.value;
            for (const piece of splitDelta(next.value)) emit({ type: 'delta', text: piece });
          }
        } catch (error) {
          providerError = error;
        } finally {
          clearTimeout(timer);
          clientSignal.removeEventListener('abort', onClientAbort);
        }
        if (reason) void iterator?.return?.()?.catch(() => {});

        // The row stays `incomplete` (as claimed) on every path except a stored completion.
        if (reason === 'client') {
          logOutcome(responseId, 'CLIENT_ABORTED');
          finish();
          return;
        }
        if (reason === 'timeout') return fail('AI_TIMEOUT');
        if (providerError !== null) {
          return fail('AI_PROVIDER_ERROR', `error=${providerError instanceof Error ? providerError.name : typeof providerError}`);
        }
        if (text.trim().length === 0) return fail('AI_EMPTY_RESPONSE');

        let stored: boolean;
        try {
          stored = await deps.store.complete(userId, responseId, text, now());
        } catch (error) {
          return fail('INTERNAL_ERROR', `error=${error instanceof Error ? error.name : typeof error}`);
        }
        // A concurrent attempt completed first: its text is the response; a retry replays it.
        if (!stored) return fail('AI_RESPONSE_CONFLICT');
        logOutcome(responseId, 'complete');
        emit({ type: 'complete', responseId, disclaimer: true });
        finish();
      })().catch((error: unknown) => fail('INTERNAL_ERROR', `error=${error instanceof Error ? error.name : typeof error}`));
    },
    cancel() {
      closed = true;
      stop('client');
    },
  });
}
