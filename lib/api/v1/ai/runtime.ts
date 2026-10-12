import { getPoolDb } from '@/db/pool';
import { deepseekCoachProvider, deepseekObjectProvider } from '@/lib/coach-model';
import type { CoachTurnDeps } from '@/lib/coach-stream';
import type { ReportDeps } from '@/lib/intelligent-report';
import type { MesocycleDeps } from '@/lib/mesocycle-draft';
import {
  claimAiResponse,
  claimCoachResponse,
  completeCoachResponse,
  findAiReport,
  loadCoachContext,
  releaseCoachResponse,
  storeAiReport,
} from './store';

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

/** Production wiring of mesocycle generation: ai_responses (kind 'mesocycle') + DeepSeek structured output. */
export function getMesocycleDeps(): MesocycleDeps {
  const db = getPoolDb();
  return {
    provider: deepseekObjectProvider,
    store: {
      claim: (userId, clientRequestId, input) => claimAiResponse(db, userId, clientRequestId, 'mesocycle', input),
      complete: (userId, responseId, attemptId, content, now) =>
        completeCoachResponse(db, userId, responseId, attemptId, content, now),
      release: (userId, responseId, attemptId, now) => releaseCoachResponse(db, userId, responseId, attemptId, now),
    },
  };
}

/** Production wiring of intelligent reports: ai_reports + DeepSeek structured output. */
export function getReportDeps(): ReportDeps {
  const db = getPoolDb();
  return {
    provider: deepseekObjectProvider,
    store: {
      find: (userId, kind, fingerprint) => findAiReport(db, userId, kind, fingerprint),
      store: (userId, kind, fingerprint, input) => storeAiReport(db, userId, kind, fingerprint, input),
    },
  };
}
