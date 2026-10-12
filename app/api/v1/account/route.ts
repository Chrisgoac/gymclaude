import { deleteAccount, DeleteAccountRequestSchema } from '@/lib/account/deletion';
import { getAccountDeletionService } from '@/lib/account/runtime';
import { readJsonBody } from '@/lib/api/v1/ai/http';
import { readFactorVerificationAge } from '@/lib/api/v1/auth';
import { enforceRateLimit } from '@/lib/api/v1/rate-limit';
import { withApiRoute } from '@/lib/api/v1/route';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';
// Borrar R2 + Postgres + Clerk de una cuenta grande puede tardar; el cron reanuda si se corta.
export const maxDuration = 60;

const MAX_DELETE_BODY_BYTES = 1024;

// Borrado completo de la cuenta del llamante. Orden: auth (withApiRoute, que aquí NO niega una cuenta
// en borrado para poder reanudarla) → body { confirm: "ELIMINAR" } → (si no está ya borrada) rate limit → autenticación reciente (solo al
// pedirlo por primera vez: `fva` de Clerk ≤ 10 min, si no 403 REAUTH_REQUIRED) → bloqueo inmediato →
// R2 → Postgres → Clerk. 200 = borrada; 202 = bloqueada y en curso (reintentar el DELETE o esperar
// al cron). La suscripción de Google Play NO se cancela: `activeSubscription.manageUrl` lleva a Play.
export const DELETE = withApiRoute(
  async (request, principal) => {
    DeleteAccountRequestSchema.parse(await readJsonBody(request, MAX_DELETE_BODY_BYTES));
    const result = await deleteAccount(getAccountDeletionService(), {
      userId: principal.userId,
      factorVerificationAge: await readFactorVerificationAge(),
      // Not spent to answer an already completed deletion (no new api_rate_buckets row afterwards).
      beforeWork: () => enforceRateLimit('account', principal.userId),
    });
    return Response.json(
      {
        status: result.status,
        step: result.step,
        retryable: result.status !== 'deleted',
        finalCleanupPending: result.finalCleanupPending,
        ...(result.errorCode ? { errorCode: result.errorCode } : {}),
        activeSubscription: result.activeSubscription,
      },
      { status: result.status === 'deleted' ? 200 : 202, headers: { 'cache-control': 'no-store' } },
    );
  },
  { allowDeletingAccount: true },
);
