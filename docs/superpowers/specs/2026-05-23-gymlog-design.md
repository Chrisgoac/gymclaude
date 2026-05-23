# GymLog — Diseño de la app personal de gimnasio

- **Fecha:** 2026-05-23
- **Estado:** Aprobado (pendiente de revisión final del usuario)
- **Nombre de trabajo:** GymLog (provisional, renombrable)

## Resumen

App personal de gimnasio para **planificar rutinas → registrar entrenos → ver progreso**, como un ciclo completo. Es una **PWA móvil** (instalable y usable offline en el gimnasio), **local-first** con **cuenta de usuario y sincronización multi-dispositivo** en la nube. Interfaz en español, kg por defecto.

La inspiración de estructura viene del programa "Baby Groot - Novice Hypertrophy" de John Meadows (12 semanas, 6 sesiones que se alternan en semanas A/B, cada ejercicio con series/reps objetivo, RPE, descanso y vídeo de técnica). Ese programa se **precarga como rutina de ejemplo**.

## Decisiones tomadas (brainstorming)

| Tema | Decisión |
|------|----------|
| Objetivo | Ciclo completo: planificar + registrar + progreso |
| Plataforma | PWA móvil (instalable, offline) |
| Datos | Local-first **+ login + sync en la nube** + exportar/importar JSON como copia |
| Ejercicios | Catálogo precargado + crear propios |
| Detalle de registro | **Simple y rápido**: peso × reps por serie (sin RPE obligatorio ni tipos) |
| Progreso | Gráfica por ejercicio + PRs + historial/calendario + volumen por grupo muscular |
| Stack auth + BD | **Clerk + Neon Postgres + Drizzle ORM** |
| Login | Email + Google; cada usuario ve solo sus datos |
| Unidades / idioma | kg (configurable) / español |

## Alcance (MVP)

### Ejercicios
- Catálogo precargado, organizado por grupo muscular.
- Crear/editar/borrar ejercicios propios.
- Campos: nombre, grupo muscular, equipamiento (barra / mancuerna / máquina / polea / peso corporal), tipo (compuesto / aislamiento), URL de vídeo (opcional), notas.

### Rutinas / Programas
- Una **rutina** contiene varias **sesiones** (días).
- Cada sesión es una lista ordenada de ejercicios con objetivo: nº de series, reps objetivo, descanso, notas.
- Soporta programas multi-sesión que se alternan (ej. Baby Groot: 6 sesiones, semanas A/B). El modelo es flexible: la rutina tiene N sesiones y el usuario elige cuál entrenar.
- "Baby Groot" se precarga como rutina de ejemplo lista para usar.

### Registrar entreno (logging)
- Iniciar una sesión desde una rutina o como entreno libre.
- Por ejercicio se apuntan series **peso × reps** (flujo rápido, pulgar-friendly).
- Autorrelleno con los valores de la última vez que se hizo ese ejercicio.
- Steppers +/− para peso y reps; marcar serie como hecha.
- Temporizador de descanso **opcional** y ligero (se puede iniciar tras marcar una serie; usa `descansoSegundos` de la sesión si existe). No es bloqueante ni obligatorio.
- Guardar entreno con fecha y notas opcionales.

### Progreso
1. **Gráfica por ejercicio**: evolución en el tiempo de peso / mejor serie / 1RM estimado.
2. **Récords personales (PRs)** por ejercicio (peso máximo, mejor serie, 1RM estimado) con **aviso al batir** uno.
3. **Historial y calendario** de entrenos hechos, con detalle por día y rachas de constancia.
4. **Volumen por grupo muscular** (series × reps × peso) por semana / grupo.

> Cálculos derivados de los sets; no requieren tablas dedicadas. 1RM estimado con fórmula estándar (Epley: `peso × (1 + reps/30)`).

### Cuenta y sincronización
- Login con Clerk (email + Google).
- Sync local-first con Neon Postgres (la nube es la fuente de verdad).
- Exportar/importar JSON como copia de seguridad manual adicional.

### Ajustes
- Unidades (kg/lb), idioma (es), tema (claro/oscuro), perfil/sesión, copia de seguridad (export/import).

### Fuera de alcance (MVP) — posibles ampliaciones futuras
- Seguimiento de peso corporal / medidas / fotos de progreso.
- RPE y series feeder/calentamiento detalladas (el registro actual es simple por decisión del usuario).
- Compartir rutinas con otros usuarios / multi-usuario social.
- Sync con motor dedicado (ElectricSQL/PowerSync/Dexie Cloud) si la estrategia LWW se queda corta.

## Modelo de datos

Entidades sincronizables. Cada registro lleva `id` (UUID generado en cliente), `updatedAt` y `deletedAt` (borrado lógico / tombstone).

- **Exercise**: id, userId (vacío = catálogo global precargado), nombre, grupoMuscular, equipamiento, tipo (compound/isolation), videoUrl?, notas?, esPersonalizado, updatedAt, deletedAt
- **Routine**: id, userId, nombre, descripción?, archivada, updatedAt, deletedAt
- **RoutineDay** (sesión): id, routineId, nombre, orden, notas?, updatedAt, deletedAt
- **RoutineExercise**: id, routineDayId, exerciseId, orden, seriesObjetivo?, repsObjetivo?, descansoSegundos?, notas?, updatedAt, deletedAt
- **WorkoutSession** (entreno realizado): id, userId, routineDayId? (vacío si fue libre), fecha, duracionSegundos?, notas?, updatedAt, deletedAt
- **LoggedExercise**: id, sessionId, exerciseId, orden, updatedAt, deletedAt
- **LoggedSet**: id, loggedExerciseId, orden, peso, reps, esCalentamiento?, updatedAt, deletedAt

Notas:
- Los IDs los genera el cliente (UUID) para que lo creado offline tenga identidad estable antes de sincronizar.
- PRs y volumen se **calculan** a partir de `LoggedSet`.

## Arquitectura

### Frontend
- **Next.js (App Router) + TypeScript**, desplegado en Vercel.
- **Tailwind + shadcn/ui**, mobile-first.
- **Recharts** para gráficas.
- **PWA**: service worker con **Serwist** para cáscara offline + manifest instalable.
- **Datos locales: Dexie (IndexedDB)**. La UI lee/escribe siempre en local → instantáneo y offline. UI reactiva con `useLiveQuery` (dexie-react-hooks).

### Backend
- **Route handlers de Next.js** (Vercel Functions / Fluid Compute) para la API de sync.
- **Drizzle ORM + Neon Postgres**.
- **Clerk** para autenticación; el `userId` de la sesión filtra todas las consultas (aislamiento por usuario).

### Sincronización (local-first)
- Cada mutación local se aplica a Dexie y se encola en una **outbox**.
- **Push** → `POST /api/sync/push`: el servidor hace upsert con **última escritura gana** (`updatedAt`). Borrados = tombstone (`deletedAt`).
- **Pull** → `GET /api/sync/pull?desde=<timestamp>`: devuelve registros cambiados desde el último sync; el cliente fusiona con LWW.
- Disparadores: al abrir la app, al recuperar conexión (`online`), y tras cambios (con debounce).
- **Indicador de estado** visible: sincronizado / pendiente / offline.
- Estrategia justificada: un único usuario en sus propios dispositivos → conflictos raros → LWW por registro es robusto y simple, sin sobre-ingeniería.

## Pantallas (navegación inferior, mobile-first)

Cuatro pestañas: **Entrenar · Rutinas · Progreso · Historial**. Perfil/Ajustes y el catálogo de Ejercicios son accesibles desde ahí (avatar arriba / selector de ejercicio).

- **Entrenar**: sesión planificada de hoy o entreno libre. Pantalla de registro grande y pulgar-friendly: añadir serie, prerellenada con la última vez, steppers +/−, marcar serie hecha, descanso opcional.
- **Rutinas**: crear/editar programas, sus sesiones y ejercicios objetivo.
- **Progreso**: gráficas por ejercicio, PRs, volumen por grupo muscular.
- **Historial**: calendario + lista de entrenos pasados + rachas.

## Errores y casos límite

- **Offline**: todo funciona contra Dexie; el sync reintenta con backoff al volver la conexión.
- **Conflictos**: última-escritura-gana por `updatedAt` + tombstones; sin diálogos al usuario.
- **Primer uso**: requiere login una vez (online); después funciona offline con la sesión cacheada.
- **Validación**: pesos y reps no negativos; reps enteras.
- **Catálogo global**: de solo lectura para el usuario; al "editar" uno global se clona como ejercicio propio.

## Estrategia de tests

- **Unitarios — lógica de sync** (merge LWW, tombstones, push/pull): la parte más delicada. Vitest.
- **Unitarios — cálculos de progreso** (1RM Epley, detección de PR, agregación de volumen). Vitest.
- **Componente — flujo de registro** (añadir/editar series, autorrelleno). React Testing Library.
- **(Opcional) E2E** del camino feliz (Playwright): iniciar sesión de rutina → registrar → verlo en historial.

## Stack técnico (resumen)

Next.js (App Router) · TypeScript · Tailwind + shadcn/ui · Dexie.js (IndexedDB) · Clerk (auth) · Neon Postgres + Drizzle ORM · Serwist (PWA) · Recharts · Vitest + Playwright · Deploy en Vercel.
