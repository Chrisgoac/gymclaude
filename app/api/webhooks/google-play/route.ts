import { randomUUID } from 'node:crypto';
import { readJsonBody } from '@/lib/api/v1/ai/http';
import { errorResponse, jsonResponse } from '@/lib/api/v1/response';
import { PubSubPushSchema, RtdnAuthUnavailableError, getRtdnRuntime, processPushMessage } from '@/lib/billing/rtdn';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

/** A DeveloperNotification is a few hundred bytes; Pub/Sub wraps it in a small JSON envelope. */
const MAX_PUSH_BODY_BYTES = 64 * 1024;

const error = (code: string, message: string, retryable: boolean) => ({ error: { code, message, retryable } });

// Google Play RTDN via Pub/Sub push. Outside /api/v1 and without a Clerk session: the key is the
// Google-signed OIDC token of the push subscription. Status codes drive Pub/Sub: 2xx acknowledges
// (processed, duplicate or permanently unusable); 401/400/415/503 make it redeliver with backoff.
export async function POST(request: Request): Promise<Response> {
  const requestId = randomUUID();
  const { verifier, play, store, now } = getRtdnRuntime();
  if (!verifier || !play) {
    console.error('[billing/rtdn] webhook not configured');
    return jsonResponse(error('BILLING_UNAVAILABLE', 'Billing notifications are not configured', true), requestId, 503);
  }

  try {
    if (await verifier.verify(request.headers.get('authorization')) !== 'ok') {
      console.warn('[billing/rtdn] rejected unauthenticated push', JSON.stringify({ requestId }));
      return jsonResponse(error('AUTH_REQUIRED', 'Authentication required', false), requestId, 401);
    }
  } catch (cause) {
    console.error('[billing/rtdn] push authentication unavailable', cause instanceof RtdnAuthUnavailableError ? cause.name : 'Error');
    return jsonResponse(error('BILLING_UNAVAILABLE', 'Authentication temporarily unavailable', true), requestId, 503);
  }

  let push;
  try {
    push = PubSubPushSchema.parse(await readJsonBody(request, MAX_PUSH_BODY_BYTES));
  } catch (cause) {
    return errorResponse(cause, requestId);
  }

  const result = await processPushMessage({ play, store, now }, push);
  if (result.status !== 200) {
    return jsonResponse(error('RETRY_LATER', 'Notification not processed yet, retry', true), requestId, result.status);
  }
  return jsonResponse({ outcome: result.outcome }, requestId);
}
