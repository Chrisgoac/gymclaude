import type { Metadata } from 'next';
import { playManageUrl } from '@/lib/account/deletion';
import { PRIVACY_POLICY_APPROVED, PRIVACY_POLICY_LAST_UPDATED } from './status';

// Política de privacidad pública de GymLog (enlazada desde la app Android y la ficha de Google Play).
// Página estática: no lee la sesión y es accesible sin iniciar sesión (proxy.ts no protege rutas).
// El texto revisable vive también en docs/play/privacy-policy.md; page.test.tsx comprueba que los
// títulos de sección de ambos coinciden.

export const metadata: Metadata = {
  title: 'Política de privacidad · GymLog',
  description: 'Qué datos trata GymLog, para qué, con quién y cómo borrarlos.',
};

const PLAY_SUBSCRIPTIONS_URL = playManageUrl();
const OWNER = '[confirmar por el dueño]';

const updatedFormat = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-2 border-2 border-foreground bg-card p-4">
      <h2 className="font-[family-name:var(--font-display)] text-xl uppercase leading-tight">{title}</h2>
      {children}
    </section>
  );
}

export default function PrivacyPage() {
  return (
    <article className="space-y-4 pb-8 text-sm leading-relaxed">
      <header className="space-y-1">
        <h1 className="font-[family-name:var(--font-display)] text-3xl uppercase leading-none">Política de privacidad</h1>
        <p>
          Cómo trata GymLog (app Android y versión web) tus datos. Última revisión:{' '}
          {updatedFormat.format(new Date(`${PRIVACY_POLICY_LAST_UPDATED}T00:00:00Z`))}.
        </p>
      </header>

      {!PRIVACY_POLICY_APPROVED && (
        <p role="note" className="border-2 border-foreground bg-primary/10 p-4 font-semibold">
          Borrador pendiente de aprobación. Este texto todavía no es la versión definitiva.
        </p>
      )}

      <Section title="Quién es el responsable">
        <p>
          El responsable del tratamiento es el desarrollador de GymLog: {OWNER} (nombre o razón social, NIF y
          dirección postal). Puedes escribirle al correo de contacto del desarrollador que aparece en la ficha de
          GymLog en Google Play.
        </p>
      </Section>

      <Section title="Qué datos tratamos">
        <ul className="list-disc space-y-1 pl-5">
          <li>
            <strong>Cuenta:</strong> tu email, tu identificador de usuario y, si entras con Google, los datos básicos
            de perfil que Google comparte con nuestro proveedor de inicio de sesión. Necesitas una cuenta para usar
            la app.
          </li>
          <li>
            <strong>Entrenos:</strong> rutinas, ejercicios personalizados, entrenamientos, series (peso, repeticiones),
            gimnasios, notas, ajustes, logros y mesociclos.
          </li>
          <li>
            <strong>Medidas corporales</strong> que tú apuntas (peso y perímetros como cintura o pecho, y las
            métricas que crees). Son opcionales.
          </li>
          <li>
            <strong>Fotos</strong> de progreso y de ejercicios que tú eliges subir. Son opcionales. La app Android
            las comprime y les quita los metadatos (EXIF), incluida la ubicación, antes de subirlas.
          </li>
          <li>
            <strong>Coach con IA</strong> (en la app Android, parte de GymLog Pro): los mensajes que escribes al Coach, sus respuestas, los
            mesociclos e informes que generas y los reportes que envías sobre respuestas incorrectas.
          </li>
          <li>
            <strong>Suscripción:</strong> el plan contratado, su estado, su fecha de fin y si se renueva. Guardamos
            una huella (hash) del justificante de compra de Google Play, no el justificante ni tus datos de pago:
            el pago lo gestiona Google Play y nunca vemos tu tarjeta.
          </li>
          <li>
            <strong>Datos técnicos:</strong> un identificador aleatorio de instalación que la app genera para
            sincronizar sin conflictos, y los datos que cualquier servidor recibe al conectarte (dirección IP,
            fecha y hora, tipo de dispositivo) en los registros de nuestros proveedores.
          </li>
        </ul>
        <p>
          GymLog no usa publicidad, ni analítica de terceros, ni herramientas de seguimiento, y no pide permisos de
          ubicación, contactos, cámara ni almacenamiento: las fotos se eligen con el selector del sistema o se toman
          con la app de cámara del dispositivo.
        </p>
      </Section>

      <Section title="Para qué los usamos">
        <ul className="list-disc space-y-1 pl-5">
          <li>Darte el servicio: guardar tus datos y sincronizarlos entre tus dispositivos y la web.</li>
          <li>Mostrarte tu progreso, estadísticas y logros.</li>
          <li>Prestar las funciones de IA de GymLog Pro cuando las usas.</li>
          <li>Comprobar con Google Play si tienes una suscripción activa.</li>
          <li>Seguridad: evitar abusos (límites de uso) y resolver errores.</li>
        </ul>
        <p>
          No vendemos tus datos ni los usamos para publicidad. Base legal: la ejecución del servicio que pides al
          crear tu cuenta y, para las medidas corporales y las fotos, que tú decides apuntarlas. {OWNER} (revisión
          legal de las bases jurídicas, en particular si las medidas corporales se consideran datos de salud).
        </p>
      </Section>

      <Section title="Coach con IA">
        <p>
          Cuando usas el Coach, generas un mesociclo o pides un informe inteligente, enviamos al proveedor de IA
          (DeepSeek) tu mensaje, la conversación reciente con el Coach y un resumen de tu entrenamiento: sesiones y
          volumen de la semana, ejercicios estancados, récords, grupos musculares y tus medidas más recientes. Para
          los mesociclos se envían además los objetivos que eliges y la lista de ejercicios disponibles. No se
          envían tu email, tu identificador de cuenta ni tus fotos.
        </p>
        <p>
          Las respuestas las genera una IA: pueden ser incorrectas o incompletas y no son consejo médico. Ante dolor,
          una lesión o dudas de salud, consulta a un profesional. Puedes reportar las respuestas del Coach desde la app; para un mesociclo o un
          informe, escríbenos al correo de contacto de la ficha de Google Play.
        </p>
      </Section>

      <Section title="Con quién los compartimos">
        <p>Solo con los proveedores que necesitamos para prestar el servicio, que los tratan por cuenta nuestra:</p>
        <ul className="list-disc space-y-1 pl-5">
          <li><strong>Clerk:</strong> inicio de sesión y gestión de cuentas.</li>
          <li><strong>Vercel:</strong> alojamiento de la web y del servidor de la app.</li>
          <li><strong>Neon:</strong> base de datos (PostgreSQL) donde se guardan tus datos sincronizados.</li>
          <li><strong>Cloudflare R2:</strong> almacenamiento de las fotos.</li>
          <li><strong>DeepSeek:</strong> modelo de IA del Coach, los mesociclos y los informes (solo cuando usas esas funciones).</li>
          <li><strong>Google Play:</strong> cobro y verificación de la suscripción.</li>
        </ul>
        <p>No compartimos tus datos con nadie más salvo obligación legal.</p>
      </Section>

      <Section title="Dónde se guardan">
        <p>
          Algunos de estos proveedores pueden tratar datos fuera del Espacio Económico Europeo (por ejemplo, en
          Estados Unidos o, en el caso de DeepSeek, en China). {OWNER} (ubicación de cada servicio y garantías de
          las transferencias internacionales, como cláusulas contractuales tipo).
        </p>
      </Section>

      <Section title="Seguridad">
        <p>
          Todas las conexiones van cifradas (HTTPS). Las fotos subidas desde la app Android se guardan en un
          almacenamiento privado y solo se pueden ver con enlaces firmados que caducan en minutos. Las fotos subidas
          con la versión web antigua se guardan en un almacenamiento público con una dirección aleatoria y
          permanente: no aparecen en ningún listado, pero cualquiera que tenga el enlace puede verlas, sin caducidad.
          Los datos de la app en tu dispositivo no se incluyen en las copias de seguridad en la nube de Android.
        </p>
      </Section>

      <Section title="Cuánto tiempo los conservamos">
        <ul className="list-disc space-y-1 pl-5">
          <li>Tus datos se conservan mientras tengas la cuenta.</li>
          <li>
            Si borras una foto, el archivo se elimina en uno o dos días. Si borras un entreno u otro elemento, se
            guarda una marca de borrado para que tus otros dispositivos también lo quiten; desaparece del todo al
            borrar la cuenta.
          </li>
          <li>
            Si borras tu cuenta, se bloquea al instante y sus datos se eliminan normalmente en minutos. Una limpieza
            final de seguridad se hace en la siguiente pasada diaria (hasta unas 24 horas después) y, si algún
            servicio falla, el borrado se reintenta cada día hasta completarse. Solo queda una huella irreversible
            (hash) de tu identificador para saber que hubo un borrado, sin tus datos.
          </li>
          <li>
            Al cerrar sesión en la app Android se borran del dispositivo los datos de esa cuenta.
          </li>
          <li>
            Las copias de seguridad de nuestros proveedores (por ejemplo, la recuperación a un momento anterior de
            la base de datos) y sus registros técnicos caducan según sus propios plazos. {OWNER} (plazos concretos).
          </li>
        </ul>
      </Section>

      <Section title="Borrar tu cuenta">
        <p>
          Puedes borrar tu cuenta y todos sus datos desde la app Android (Ajustes → Cuenta y privacidad → Eliminar
          cuenta) o desde <a className="font-semibold underline" href="/delete-account">la página de borrado de
          cuenta</a>. Borrar la cuenta <strong>no cancela</strong> una suscripción a GymLog Pro: cancélala en{' '}
          <a className="font-semibold underline" href={PLAY_SUBSCRIPTIONS_URL} target="_blank" rel="noreferrer">
            tus suscripciones de Google Play
          </a>
          .
        </p>
      </Section>

      <Section title="Tus derechos">
        <p>
          Puedes acceder a tus datos, corregirlos, exportarlos (la app exporta una copia de tus entrenos, rutinas, medidas y
          ajustes),
          oponerte a su tratamiento, limitarlo o pedir su borrado escribiendo al correo de contacto del
          desarrollador que aparece en la ficha de GymLog en Google Play, desde el correo de tu cuenta. También
          puedes reclamar ante la Agencia Española de Protección de Datos (aepd.es).
        </p>
      </Section>

      <Section title="Menores">
        <p>
          GymLog no está dirigida a menores de {OWNER} (edad mínima) años y no tratamos a sabiendas datos de menores
          de esa edad.
        </p>
      </Section>

      <Section title="Cambios en esta política">
        <p>
          Si cambiamos esta política, actualizaremos la fecha de revisión de esta página y, si el cambio es
          importante, te avisaremos en la app.
        </p>
      </Section>
    </article>
  );
}
