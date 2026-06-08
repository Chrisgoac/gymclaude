# Progresión automática (A) — Diseño

**Fecha:** 2026-06-08
**Estado:** aprobado para plan
**Parte de:** roadmap A → C → B (A = motor de progresión; C = insights/estancamiento/deload/resumen/mapa muscular; B = coach IA). Solo se diseña **A** aquí; C y B quedan como backlog (ver final).

## Problema

Hoy la app autorrellena en silencio el **último** peso del ejercicio (mismo gimnasio) y muestra el bloque "Última vez / Objetivo". Pero no sugiere **qué deberías hacer hoy**: no aplica sobrecarga progresiva. El usuario quiere que la app pase de *cuaderno* a *entrenador silencioso*, sugiriendo el **próximo objetivo** (subir peso o reps) a partir de lo que hizo y del objetivo de la rutina.

## Decisiones tomadas (vía brainstorming)

- **Regla de progresión configurable** desde Ajustes, **global** (un ajuste para todo): `doble` | `objetivo` | `repite` | `off`. Default: `objetivo`.
- **Incremento de peso = inferido del historial + override por ejercicio.** El salto real es propiedad de la máquina concreta, no del tipo de equipamiento. Se deduce de los pesos ya registrados (filtrados por gimnasio, lo que resuelve el multi-gym gratis). Default por equipamiento solo como semilla cuando no hay historial.
- **Presentación:** autorrelleno del valor sugerido en la nueva serie **+ badge explicativo** ("▲ +5kg · completaste 3×12"). No es magia: explica el porqué.
- **Alcance:** la progresión actúa solo al entrenar **desde una rutina** (hay objetivo). En **entreno libre** se mantiene el comportamiento actual (autorrellena último peso, sin sugerir subida).

## Modelo de datos (cambios mínimos)

- `RoutineExercise`: añadir `repsObjetivoMin?: number` (opcional). El `repsObjetivo` existente pasa a interpretarse como el **tope** del rango. Si no hay `repsObjetivoMin` y el modo es `doble`, el rango por defecto es `[repsObjetivo − 4, repsObjetivo]` (con mínimo 1).
- `Exercise`: añadir `incrementoKg?: number` (opcional). Es el **override** manual del salto; gana sobre la inferencia.
- Ajustes globales (sincronizables, en la tabla `syncState` / settings existentes):
  - `modoProgresion`: `'doble' | 'objetivo' | 'repite' | 'off'` (default `'objetivo'`).
  - `incrementosPorEquipamiento`: mapa equipamiento → kg. Defaults semilla: `barra: 2.5`, `mancuerna: 2`, `maquina: 5`, `polea: 2.5`. `peso corporal` → progresa por reps (sin peso).

No se crean tablas nuevas. Migración Dexie: nueva versión que solo añade campos opcionales (no requiere upgrade de datos; los registros antiguos los dejan `undefined`).

## Inferencia del salto — `inferirIncremento(exerciseId, gymId)`

Función **pura** sobre el historial (testeable sin DB si recibe los pesos ya cargados):

1. Toma los **pesos distintos** de las series **de trabajo** (excluye `esCalentamiento`) ya registradas para ese ejercicio en ese gimnasio.
2. Ordena, calcula las diferencias entre consecutivos y obtiene el **GCD** de esas diferencias → salto fundamental. Ej.: `40, 45, 50, 60` ⇒ diffs `5,5,10` ⇒ gcd `5`.
   - Implementación: escalar ×100 para trabajar con enteros (evita errores de coma flotante con 2,5 / 1,25), gcd entero, dividir.
3. Para evitar ruido (p. ej. un peso suelto raro), **redondear** el resultado al valor plausible más cercano del set sano `{0.5, 1, 1.25, 2, 2.5, 5, 7.5, 10}`.
4. **Prioridad** del salto a usar:
   1. `Exercise.incrementoKg` (override manual), si existe.
   2. Inferencia (si hay ≥ 2 pesos distintos).
   3. Default por equipamiento del ajuste `incrementosPorEquipamiento`.

## Motor — `calcularSugerencia(...)`

Función **pura**. Entradas:
- Último rendimiento: **todas las series de trabajo** de la última sesión del ejercicio en el mismo gym (peso × reps por serie). (Hoy `getLastPerformance` devuelve solo el último set; se ampliará a `getLastWorkingSets` o equivalente que devuelva todas las de trabajo de esa sesión.)
- Objetivo de rutina: `seriesObjetivo`, `repsObjetivo` (tope), `repsObjetivoMin`.
- `modoProgresion`.
- Salto resuelto (de `inferirIncremento`).
- Equipamiento del ejercicio (para el caso peso corporal).

**Definición de éxito:** todas las series de trabajo de la última vez alcanzaron las reps objetivo (el **tope** del rango en `doble`; `repsObjetivo` en `objetivo`). Las series de calentamiento se ignoran.

Reglas por modo:
- **`doble`** (doble progresión): éxito → `pesoSugerido = ultimoPeso + salto`, `repsSugeridas = repsObjetivoMin`. Si no → mismo peso, `repsSugeridas` = apuntar a +1 rep hacia el tope (sin pasarse del tope).
- **`objetivo`**: éxito → `+salto`, mismas reps objetivo. Si no → mismo peso, mismas reps objetivo.
- **`repite`**: éxito → `+salto`. Si no → mismo peso. (Sin lógica de reps; mantiene las reps del objetivo si las hay.)
- **Peso corporal** (cualquier modo con peso): nunca toca el peso; progresa subiendo reps.
- **`off` / sin historial / entreno libre:** comportamiento actual (autorrellena último peso si lo hay, sin sugerir subida ni badge de progresión).

Salida: `{ pesoSugerido: number, repsSugeridas: number, motivo: string }`. `motivo` alimenta el badge (ej. `'subio'` / `'repite-fallo-series'` / `'sin-historial'` …) y la UI lo traduce a texto.

## UI al entrenar

- La **nueva serie arranca** con `pesoSugerido × repsSugeridas` (steppers +/− editables; el usuario siempre puede ajustar).
- **Badge explicativo** corto bajo el ejercicio, reutilizando/junto al bloque "Última vez / Objetivo" ya existente. Ejemplos:
  - `▲ +5 kg · completaste 3×12`
  - `= repite · faltó 1 serie`
  - `▲ +1 rep · doble progresión`
- Estética: sistema "Brutalist Iron" existente (bordes 2px, radio 0, OKLch, Anton/Archivo/Space Mono, iconos Lucide).

## Ajustes

- Nueva sección **"Progresión"**:
  - Selector de **modo** (`doble` / `objetivo` / `repite` / `off`).
  - Tabla editable de **incrementos por equipamiento** (semillas por defecto).
- **Override por ejercicio**: campo `incrementoKg` opcional en la ficha del ejercicio.

## Estrategia de tests (Vitest)

- `inferirIncremento`: GCD correcto (incl. 2,5 / 1,25), redondeo de ruido al set sano, sin historial → default por equipamiento, override gana.
- `calcularSugerencia`: matriz modos (`doble`/`objetivo`/`repite`) × (éxito / fallo parcial de series / fallo total) × peso corporal × sin-historial × `off`.
- `getLastWorkingSets` (o ampliación de `getLastPerformance`): filtra calentamiento y mismo gym.
- Componente (React Testing Library): la card de entreno arranca con el valor sugerido y muestra el badge correcto según el motivo.

## Casos límite

- **Sin historial** del ejercicio → sin sugerencia de subida (igual que libre); salto = default por equipamiento.
- **Cambio de gym** → solo cuenta el último rendimiento del **mismo** gym (ya filtrado en la consulta) y la inferencia es por gym.
- **Series de calentamiento** ignoradas tanto en éxito como en la inferencia del salto.
- **Override** manual siempre gana sobre la inferencia.
- **`repsObjetivoMin` > `repsObjetivo`** o valores inválidos → se clampa (min ≤ tope, min ≥ 1).
- **Peso corporal con peso 0** → progresión por reps; nunca sugiere peso.

## Fuera de alcance (backlog — encaje con C y B)

- **C (insights, siguiente):** detección de **estancamiento** (varias sesiones sin éxito → no sube), sugerencia de **deload**, resumen **semanal** (volumen/PR/adherencia) y **mapa muscular**. Reutiliza la señal de "éxito/fallo" y el motor de análisis de A.
- **B (coach IA, después):** chat/insights con IA sobre las señales ya calculadas por A y C (mucho más barato y útil que masticar el historial crudo).
