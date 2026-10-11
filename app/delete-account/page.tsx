import type { Metadata } from 'next';
import { auth } from '@clerk/nextjs/server';
import { SignInButton } from '@clerk/nextjs';
import { loadDeletionPageState, type DeletionPageState } from '@/lib/account/runtime';
import { playManageUrl } from '@/lib/account/deletion';
import { DeleteAccountForm } from './delete-account-form';

export const metadata: Metadata = {
  title: 'Borrar tu cuenta · GymLog',
  description: 'Cómo borrar tu cuenta de GymLog y todos sus datos, desde la app o desde la web.',
};

// Lee la sesión de Clerk en cada petición: nunca se prerenderiza.
export const dynamic = 'force-dynamic';

const PLAY_SUBSCRIPTIONS_URL = playManageUrl();

const dateFormat = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'long', year: 'numeric' });

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-2 border-2 border-foreground bg-card p-4">
      <h2 className="font-[family-name:var(--font-display)] text-xl uppercase leading-tight">{title}</h2>
      {children}
    </section>
  );
}

function SubscriptionNotice({ state }: { state: DeletionPageState }) {
  const sub = state.activeSubscription;
  if (!sub) return null;
  return (
    <div role="note" className="border-2 border-foreground bg-primary/10 p-4 text-sm">
      <p className="font-semibold">Tienes una suscripción a GymLog Pro en Google Play.</p>
      <p className="mt-1">
        {sub.willRenew
          ? 'Borrar la cuenta NO la cancela: Google Play seguirá cobrándote hasta que la canceles allí.'
          : `Ya no se renovará${sub.validUntil ? ` (termina el ${dateFormat.format(sub.validUntil)})` : ''}, pero el acceso pagado se pierde al borrar la cuenta.`}
      </p>
      <a className="mt-2 inline-block font-semibold underline" href={sub.manageUrl} target="_blank" rel="noreferrer">
        Gestionar la suscripción en Google Play
      </a>
    </div>
  );
}

export default async function DeleteAccountPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const { userId } = await auth();
  const justDeleted = (await searchParams).deleted === '1';
  const state = userId ? await loadDeletionPageState(userId) : null;

  return (
    <article className="space-y-4 pb-8 text-sm leading-relaxed">
      <header className="space-y-1">
        <h1 className="font-[family-name:var(--font-display)] text-3xl uppercase leading-none">Borrar tu cuenta</h1>
        <p>
          Puedes borrar tu cuenta de GymLog y todos sus datos desde la app Android (Ajustes → Cuenta → Borrar
          cuenta) o desde esta página, en cualquier navegador.
        </p>
      </header>

      {justDeleted && !userId && (
        <p role="status" className="border-2 border-foreground bg-card p-4">
          Tu cuenta se ha borrado. Gracias por usar GymLog.
        </p>
      )}

      <Section title="Qué se borra">
        <ul className="list-disc space-y-1 pl-5">
          <li>Tu cuenta de acceso (inicio de sesión) y tu perfil.</li>
          <li>Todos tus entrenos, rutinas, ejercicios personalizados, series, gimnasios, ajustes y logros.</li>
          <li>Tus métricas corporales y tus fotos (de progreso y de ejercicios).</li>
          <li>Tus conversaciones con el Coach, mesociclos, informes de IA y reportes que hayas enviado.</li>
          <li>El vínculo entre tu cuenta y tu suscripción, y su historial de verificación.</li>
        </ul>
        <p>
          El acceso se bloquea en cuanto confirmas y tus datos se borran normalmente en minutos; una limpieza final
          de seguridad, o un reintento si algún servicio falla, puede tardar como máximo unos días. No se puede
          deshacer. Las
          copias que hayas exportado tú y los datos guardados sin conexión en tus dispositivos no se borran: elimina
          la app o sus datos del dispositivo si quieres quitarlos también.
        </p>
      </Section>

      <Section title="Tu suscripción de Google Play">
        <p>
          Borrar la cuenta <strong>no cancela</strong> una suscripción a GymLog Pro: la gestiona Google Play y
          tienes que cancelarla allí, antes o después de borrar la cuenta, para que no te siga cobrando.
        </p>
        <a className="inline-block font-semibold underline" href={PLAY_SUBSCRIPTIONS_URL} target="_blank" rel="noreferrer">
          Abrir mis suscripciones en Google Play
        </a>
      </Section>

      {userId && state ? (
        <Section title="Borrar mi cuenta">
          {state.deleting ? (
            <p role="status">
              Ya has pedido borrar esta cuenta: está bloqueada y el borrado está en curso. No necesitas hacer nada más.
            </p>
          ) : (
            <>
              <SubscriptionNotice state={state} />
              <p>
                Por seguridad, si no has iniciado sesión en los últimos 10 minutos te pediremos que confirmes tu
                identidad antes de borrar.
              </p>
              <DeleteAccountForm />
            </>
          )}
        </Section>
      ) : (
        <Section title="Borrar mi cuenta">
          <p>Inicia sesión con la cuenta que quieres borrar para continuar.</p>
          <SignInButton mode="modal" forceRedirectUrl="/delete-account">
            <button
              type="button"
              className="border-2 border-foreground bg-primary px-4 py-2 font-semibold uppercase text-primary-foreground"
            >
              Iniciar sesión
            </button>
          </SignInButton>
          <p>
            ¿No puedes iniciar sesión? Escríbenos al correo de contacto del desarrollador que aparece en la ficha de
            GymLog en Google Play, desde el correo de tu cuenta, y la borraremos por ti.
          </p>
        </Section>
      )}
    </article>
  );
}
