import { getPoolDb } from '@/db/pool';
import { deepseekCoachProvider } from '@/lib/coach-model';
import type { CoachTurnDeps } from '@/lib/coach-stream';
import { claimCoachResponse, completeCoachResponse, loadCoachContext, releaseCoachResponse } from './store';

/** Production wiring of the Coach (Postgres + DeepSeek). Route tests mock this module. */
export function getCoachDeps(): CoachTurnDeps {
  const db = getPoolDb();
  return {
    provider: deepseekCoachProvider,
    store: {
      claim: (userId, clientMessageId, input) => claimCoachResponse(db, userId, clientMessageId, input),
      loadContext: (userId, limitTurns, maxChars, excludeId) => loadCoachContext(db, userId, limitTurns, maxChars, excludeId),
      complete: (userId, responseId, attemptId, content, now) =>
        completeCoachResponse(db, userId, responseId, attemptId, content, now),
      release: (userId, responseId, attemptId, now) => releaseCoachResponse(db, userId, responseId, attemptId, now),
    },
  };
}
