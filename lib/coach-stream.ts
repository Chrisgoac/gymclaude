import { MAX_STREAM_DELTA_CHARS, type AiStreamEvent, type CoachRequest } from '@/lib/api/v1/ai/contracts';
import type { CoachClaimInput, CoachContextTurn, CoachResponseClaim } from '@/lib/api/v1/ai/store';
import type { CoachModelMessage, CoachStreamProvider } from '@/lib/coach-model';
import { coachSystemPrompt } from '@/lib/coach-prompt';

// One Coach turn of POST /api/v1/ai/coach as a newline-delimited JSON stream (application/x-ndjson):
//   start{clientMessageId, responseId} → delta{text}* → complete{responseId, disclaimer: true}
//                                                     | error{code, retryable}
// The response row (ai_responses) is the only thing the server writes: the user's message lives in
// the client's synced coach_messages, so a retry never duplicates it. A retry of the same
// clientMessageId replays a complete response without calling the model, regenerates an incomplete
// one under the same responseId once no attempt holds its lease, or gets 409 AI_RESPONSE_IN_PROGRESS
// (before any streaming) while one does. Only the attempt holding the lease can complete the row.

/** Newest user/assistant turns sent as context. */
export const COACH_CONTEXT_TURNS = 20;
/** Char budget of that context. */
export const COACH_CONTEXT_CHARS = 16_000;
export const COACH_MAX_OUTPUT_TOKENS = 1500;
/** The model call is aborted after this long (route maxDuration is 60 s). */
export const COACH_TIMEOUT_MS = 45_000;
/** An attempt's lease outlives its model timeout by this much (covers the final DB write). */
export const COACH_LEASE_MARGIN_MS = 15_000;

export const NDJSON_CONTENT_TYPE = 'application/x-ndjson; charset=utf-8';

/** Persistence the Coach turn needs (lib/api/v1/ai/store.ts in production, in-memory in tests). */
export interface CoachResponseStore {
  /** Throws 409 AI_RESPONSE_IN_PROGRESS while another attempt holds a live lease. */
  claim(userId: string, clientMessageId: string, input: CoachClaimInput): Promise<CoachResponseClaim>;
  loadContext(userId: string, limitTurns: number, maxChars: number, excludeId: string): Promise<CoachContextTurn[]>;
  /** false when this attempt no longer owns the row (taken over after its lease expired). */
  complete(userId: string, responseId: string, attemptId: string, content: string, now: number): Promise<boolean>;
  /** Drops this attempt's lease so a retry can start immediately. */
  release(userId: string, responseId: string, attemptId: string, now: number): Promise<void>;
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

// Ids and codes only: never prompt, user text, snapshot or model output. Failures warn, the rest info.
const logOutcome = (responseId: string, outcome: string, extra?: string, level: 'info' | 'warn' = 'warn') =>
  console[level](`[api/v1/ai/coach] responseId=${responseId} outcome=${outcome}${extra ? ` ${extra}` : ''}`);
const errorName = (error: unknown) => `error=${error instanceof Error ? error.name : typeof error}`;

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
  const newId = deps.newId ?? (() => crypto.randomUUID());
  const timeoutMs = deps.timeoutMs ?? COACH_TIMEOUT_MS;
  const claimedAt = now();
  // A live attempt of the same clientMessageId makes this throw 409 before anything is streamed.
  const claim = await deps.store.claim(userId, request.clientMessageId, {
    newId: newId(),
    attemptId: newId(),
    now: claimedAt,
    leaseExpiresAt: claimedAt + timeoutMs + COACH_LEASE_MARGIN_MS,
  });
  const start: AiStreamEvent = { type: 'start', clientMessageId: request.clientMessageId, responseId: claim.responseId };

  if (claim.status === 'complete') {
    logOutcome(claim.responseId, 'replay', undefined, 'info');
    return new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encodeEvent(start));
        for (const text of splitDelta(claim.content)) controller.enqueue(encodeEvent({ type: 'delta', text }));
        controller.enqueue(encodeEvent({ type: 'complete', responseId: claim.responseId, disclaimer: true }));
        controller.close();
      },
    });
  }

  const { responseId, attemptId } = claim;
  let context: CoachContextTurn[];
  try {
    context = await deps.store.loadContext(userId, COACH_CONTEXT_TURNS, COACH_CONTEXT_CHARS, request.clientMessageId);
  } catch (error) {
    await deps.store.release(userId, responseId, attemptId, now()).catch(() => {});
    throw error;
  }
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
      const release = () => deps.store.release(userId, responseId, attemptId, now()).catch((error: unknown) => {
        logOutcome(responseId, 'RELEASE_FAILED', errorName(error));
      });
      /** Ends the attempt without a response: lease released (retry can start at once), error event. */
      const fail = async (code: CoachStreamErrorCode, extra?: string, owned = true) => {
        logOutcome(responseId, code, extra);
        if (owned) await release();
        emit({ type: 'error', code, retryable: true });
        finish();
      };

      void (async () => {
        if (clientSignal.aborted) stop('client');
        clientSignal.addEventListener('abort', onClientAbort, { once: true });
        const timer = setTimeout(() => stop('timeout'), timeoutMs);
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
          logOutcome(responseId, 'CLIENT_ABORTED', undefined, 'info');
          await release();
          finish();
          return;
        }
        if (reason === 'timeout') return fail('AI_TIMEOUT');
        if (providerError !== null) return fail('AI_PROVIDER_ERROR', errorName(providerError));
        if (text.trim().length === 0) return fail('AI_EMPTY_RESPONSE');

        let stored: boolean;
        try {
          stored = await deps.store.complete(userId, responseId, attemptId, text, now());
        } catch (error) {
          return fail('INTERNAL_ERROR', errorName(error));
        }
        // This attempt lost the row (its lease expired and a retry took over): its text is not the
        // response. Nothing to release.
        if (!stored) return fail('AI_RESPONSE_CONFLICT', undefined, false);
        logOutcome(responseId, 'complete', undefined, 'info');
        emit({ type: 'complete', responseId, disclaimer: true });
        finish();
      })().catch((error: unknown) => fail('INTERNAL_ERROR', errorName(error)));
    },
    cancel() {
      closed = true;
      stop('client');
    },
  });
}
