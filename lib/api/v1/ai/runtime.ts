import { getPoolDb } from '@/db/pool';
import { deepseekCoachProvider } from '@/lib/coach-model';
import type { CoachTurnDeps } from '@/lib/coach-stream';
import { claimCoachResponse, completeCoachResponse, loadCoachContext } from './store';

/** Production wiring of the Coach (Postgres + DeepSeek). Route tests mock this module. */
export function getCoachDeps(): CoachTurnDeps {
  const db = getPoolDb();
  return {
    provider: deepseekCoachProvider,
    store: {
      claim: (userId, clientMessageId, newId, now) => claimCoachResponse(db, userId, clientMessageId, newId, now),
      loadContext: (userId, limitTurns, maxChars, excludeId) => loadCoachContext(db, userId, limitTurns, maxChars, excludeId),
      complete: (userId, responseId, content, now) => completeCoachResponse(db, userId, responseId, content, now),
    },
  };
}
