# Fase 2 Android — comprobaciones previas a la verificación (Task 9)

Lista corta para repasar antes de rellenar `android-phase-02-platform.md`. Se suma a los puntos
del plan (bootstrap con conteos, reanudación tras matar el proceso, mutación offline sin duplicar,
cambio de cuenta sin cruce de datos).

- [ ] **El backend acepta los JWT nativos de Clerk.** Con una sesión iniciada en la app, una
  sincronización contra `https://gym.chrisgoac.dev` responde 200 (no 401/403). El backend no
  debe rechazar el token por `authorizedParties`/`azp`: hoy `proxy.ts` usa `clerkMiddleware()`
  sin `authorizedParties`. Si alguien lo añade, debe incluir el origen que Clerk pone en los
  tokens de la app Android, o la app mostrará «El servidor rechazó la sesión» en Ajustes →
  Sincronización.
