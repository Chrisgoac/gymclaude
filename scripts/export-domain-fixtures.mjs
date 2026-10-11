// Exports the shared PWA <-> Android domain parity fixtures (contracts/domain/*-cases.json) by running the
// TypeScript implementation. Inputs are listed here; expected values are whatever the TS code returns, and
// are reviewed by hand in the diff. Deterministic: fixed inputs, explicit time zones, 2-space JSON.
// Usage: npm run export:domain-fixtures   (tsx loads the TypeScript sources)
// The TS tests (lib/*.test.ts) run these same files against the TS code, and Android's DomainParityTest
// runs them against the Kotlin port. progression.json / routine-rotation.json are older, hand-maintained.
// fake-indexeddb first: the stats functions read the PWA's Dexie database (seeded per case below).
import 'fake-indexeddb/auto';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { periodoASinceTs, inicioSemana } from '../lib/period.ts';
import { formatHaceDias, formatSegundos } from '../lib/fecha.ts';
import { parseEnteroOpt, parseEntero, parseDecimal } from '../lib/num.ts';
import { resumenSerie } from '../lib/body-stats.ts';
import {
  estimar1RM, getExerciseProgress, getExercisePRs, getVolumeByMuscle, getVolumenSemanaByMuscle, getPeriodSummary,
  getWeeklySummary, getWeeklyVolume, weeklyVolumeDeltas, getCurrentStreakDays, getPRsThisWeek, getLastTrainedByMuscle,
  listEstancados, getRachaSemanal,
} from '../lib/repositories/stats.ts';
import { detectarEstancamiento, filasEquilibrio, DELOAD_CONSEJO } from '../lib/insights.ts';
import { filtroAGymId } from '../lib/gym-filter.ts';
import { LOGROS_DEF, evaluarLogros, calcularRacha } from '../lib/logros.ts';
import { reconciliarLogros } from '../lib/reconciliar-logros.ts';
import { getLogroMetricas, listPRs } from '../lib/repositories/stats.ts';
import { construirSnapshot } from '../lib/coach-snapshot.ts';
import { db } from '../lib/db/database.ts';
import { MUSCLE_GROUPS } from '../lib/db/types.ts';

const DAY = 86_400_000;
const iso = (s) => Date.parse(s);

/** Runs fn with the process time zone set to `tz` (JS Date follows process.env.TZ at runtime on Node). */
export function inZone(tz, fn) {
  const previous = process.env.TZ;
  process.env.TZ = tz;
  try {
    return fn();
  } finally {
    if (previous === undefined) delete process.env.TZ;
    else process.env.TZ = previous;
  }
}

/** Async inZone: fn's awaits all run with `tz` set (callers never run two of these concurrently). */
async function inZoneAsync(tz, fn) {
  const previous = process.env.TZ;
  process.env.TZ = tz;
  try {
    return await fn();
  } finally {
    if (previous === undefined) delete process.env.TZ;
    else process.env.TZ = previous;
  }
}

/** JSON cannot carry Infinity/NaN: numbers outside the finite range travel as strings. */
const num = (n) => (Number.isFinite(n) ? n : String(n));

const ZONES = ['UTC', 'Europe/Madrid', 'America/New_York', 'Asia/Kolkata', 'Pacific/Auckland'];

function periodCases() {
  const nows = [1_000_000 * DAY, iso('2026-06-10T12:00:00Z'), iso('2026-03-29T00:30:00Z')];
  const since = [];
  for (const periodo of ['4s', '3m', 'ano', 'todo']) {
    for (const ahora of nows) since.push({ periodo, ahora, expected: periodoASinceTs(periodo, ahora) });
  }
  // Monday 00:00 local of the week containing `ts`, around week boundaries and DST changes.
  const instants = {
    UTC: ['2026-06-10T12:00:00Z', '2026-06-14T23:59:59.999Z', '2026-06-15T00:00:00Z', '2026-06-08T00:00:00Z', '2026-01-01T00:00:00Z'],
    'Europe/Madrid': [
      '2026-06-10T12:00:00Z', '2026-06-14T21:59:59.999Z', '2026-06-14T22:00:00Z', '2026-03-29T10:00:00Z',
      '2026-03-30T00:30:00Z', '2026-10-25T10:00:00Z', '2026-10-26T00:30:00Z', '2025-12-31T23:30:00Z',
    ],
    'America/New_York': ['2026-03-08T12:00:00Z', '2026-03-09T03:59:59Z', '2026-11-01T12:00:00Z', '2026-11-02T04:59:59Z', '2026-06-10T03:00:00Z'],
    'Asia/Kolkata': ['2026-06-14T18:29:59Z', '2026-06-14T18:30:00Z', '2026-06-10T12:00:00Z'],
    'Pacific/Auckland': ['2026-04-04T10:00:00Z', '2026-04-05T12:00:00Z', '2026-09-26T10:00:00Z', '2026-09-27T12:00:00Z', '2026-06-10T12:00:00Z'],
  };
  const weekStart = [];
  for (const zone of ZONES) {
    for (const at of instants[zone]) {
      weekStart.push({ zone, at: iso(at), expected: inZone(zone, () => inicioSemana(iso(at))) });
    }
  }
  return { description: "Cases for lib/period.ts (periodoASinceTs, inicioSemana) and its Android port (core:domain time/Periods.kt). Times are epoch ms; 'expected' 0 = no lower bound. weekStart: Monday 00:00 in 'zone' of the week containing 'at'.", since, weekStart };
}

function fechaCases() {
  const secondsInputs = [0, 5, 59, 60, 65, 599, 600, 3599, 3600, 3725, 59.9, 0.4, -3, -0.5];
  const formatSegundosCases = secondsInputs.map((s) => ({ seconds: s, expected: formatSegundos(s) }));
  const base = { UTC: '2026-06-07T10:00:00Z', 'Europe/Madrid': '2026-06-07T08:00:00Z', 'America/New_York': '2026-06-07T14:00:00Z', 'Pacific/Auckland': '2026-06-07T00:00:00Z' };
  const offsets = ['2026-06-07T07:00:00Z', '2026-06-06T21:30:00Z', '2026-06-06T23:30:00Z', '2026-06-04T08:00:00Z', '2026-05-07T08:00:00Z', '2026-06-08T08:00:00Z', '2026-03-28T23:30:00Z', '2026-03-29T10:00:00Z', '2026-10-24T22:30:00Z'];
  const formatHaceDiasCases = [];
  for (const [zone, now] of Object.entries(base)) {
    for (const ts of offsets) {
      formatHaceDiasCases.push({ zone, now: iso(now), ts: iso(ts), expected: inZone(zone, () => formatHaceDias(iso(ts), iso(now))) });
    }
  }
  return { description: "Cases for lib/fecha.ts (formatSegundos, formatHaceDias) and its Android port (core:domain time/Periods.kt). formatHaceDias counts local calendar days in 'zone'. Times are epoch ms.", formatSegundos: formatSegundosCases, formatHaceDias: formatHaceDiasCases };
}

function numCases() {
  const inputs = ['', '   ', '0', '5', ' 12 ', '12.4', '12.5', '12.6', '0.5', '1.5', '2.5', '-0.4', '-1', '-3.7', 'abc', '1,5', '1e3', '1e-3', '0x10', '0b11', '0o17', '.5', '5.', '+7', '1 2', 'Infinity', '-Infinity', '1e999', '100.25', '62.5', '1_000'];
  const row = (fn) => inputs.map((text) => ({ text, expected: (() => { const v = fn(text); return v === undefined ? null : num(v); })() }));
  const pairs = [[100, 1], [100, 0], [100, -2], [100, 2], [100, 5], [100, 10], [60, 8], [62.5, 6], [0, 5], [82.5, 3], [20, 12], [142.5, 1], [100, 30], [57.3, 7]];
  return {
    description: "Cases for lib/num.ts (parseEnteroOpt, parseEntero, parseDecimal) and estimar1RM (Epley, lib/repositories/stats.ts), ported to core:domain math/Numbers.kt. null = undefined (parseEnteroOpt); a string expected value is a non-finite number (Infinity).",
    parseEnteroOpt: row(parseEnteroOpt),
    parseEntero: row(parseEntero),
    parseDecimal: row(parseDecimal),
    estimar1RM: pairs.map(([peso, reps]) => ({ peso, reps, expected: estimar1RM(peso, reps) })),
  };
}

function bodyStatsCases() {
  const m = (valor, fecha) => ({ id: String(fecha), userId: null, tipo: 'peso', valor, fecha, updatedAt: fecha, deletedAt: null });
  const series = {
    empty: [],
    single: [m(80, iso('2026-06-01T10:00:00Z'))],
    loss: [m(80, iso('2026-06-01T10:00:00Z')), m(78.5, iso('2026-06-08T10:00:00Z')), m(77, iso('2026-06-15T10:00:00Z'))],
    gain: [m(60.2, iso('2026-01-31T23:30:00Z')), m(61.7, iso('2026-02-01T00:30:00Z'))],
    flat: [m(70, iso('2026-06-01T10:00:00Z')), m(70, iso('2026-06-02T10:00:00Z'))],
    unsorted: [m(75.5, iso('2026-06-10T10:00:00Z')), m(74, iso('2026-06-03T10:00:00Z')), m(76, iso('2026-06-20T10:00:00Z'))],
    floating: [m(0.1, iso('2026-06-01T10:00:00Z')), m(0.3, iso('2026-06-02T10:00:00Z'))],
  };
  const cases = [];
  for (const [name, metrics] of Object.entries(series)) {
    for (const zone of name === 'gain' ? ['UTC', 'Europe/Madrid', 'America/New_York'] : ['Europe/Madrid']) {
      cases.push({ name: `${name} @ ${zone}`, zone, metrics: metrics.map(({ valor, fecha }) => ({ valor, fecha })), expected: inZone(zone, () => resumenSerie(metrics)) });
    }
  }
  return { description: "Cases for lib/body-stats.ts resumenSerie and its Android port (core:domain math/BodySeries in Numbers.kt). Metrics are in chronological order as the PWA passes them (the 'unsorted' case documents that resumenSerie does NOT sort). Point labels are dd/MM in 'zone'. Weight in kg.", cases };
}

// ---- insight-cases.json: lib/repositories/stats.ts, lib/insights.ts, lib/gym-filter.ts, lib/coach-snapshot.ts ----

const DELETED = 1;
/** Compact dataset builders: [id, fecha ISO, gymId, finished, deleted?]. */
const ses = (id, at, gymId, finished = true, deleted = false) => ({ id, fecha: iso(at), gymId, duracionSegundos: finished ? 3600 : null, deletedAt: deleted ? DELETED : null });
const le = (id, sessionId, exerciseId, orden = 0, deleted = false) => ({ id, sessionId, exerciseId, orden, deletedAt: deleted ? DELETED : null });
const set = (id, loggedExerciseId, orden, peso, reps, opts = {}) => ({ id, loggedExerciseId, orden, peso, reps, esCalentamiento: opts.warmup ?? false, deletedAt: opts.deleted ? DELETED : null });
const exercise = (id, nombre, grupoMuscular, deleted = false) => ({ id, nombre, grupoMuscular, deletedAt: deleted ? DELETED : null });

const EXERCISES = [
  exercise('ex-press', 'Press de banca', 'pecho'),
  exercise('ex-squat', 'Sentadilla', 'cuadriceps'),
  exercise('ex-curl', 'Curl de bíceps', 'biceps'),
  exercise('ex-calf', 'Gemelo de pie', 'gemelo'),
  // A deleted custom exercise: the PWA still resolves its name and group (bulkGet ignores deletedAt).
  exercise('ex-row', 'Remo propio', 'espalda', true),
];

/**
 * Main history (Europe/Madrid, now = Tue 2026-06-09 22:00 local): a stalled press, an improving squat,
 * PRs this week by weight (squat), by 1RM at equal weight (curl) and by a warm-up set (row: the PWA counts
 * warm-ups in every metric), two sessions with the same `fecha` (merged into one progress point), an
 * unfinished session (counted), an empty session, an exercise missing from the catalog ('ex-ghost': name
 * '—', no muscle group), a logged exercise without sets ('ex-calf') and tombstones at every level (deleted session, deleted logged exercise with a
 * live set, deleted sets) that must not count.
 */
function historyDataset() {
  return {
    sessions: [
      ses('s01', '2026-04-06T08:00:00Z', 'g1'),
      ses('s02', '2026-04-20T08:00:00Z', 'g1'),
      ses('s03', '2026-05-04T08:00:00Z', 'g2'),
      ses('s10', '2026-05-11T08:00:00Z', 'g1'),
      ses('s04', '2026-05-18T08:00:00Z', null),
      ses('s05', '2026-05-25T08:00:00Z', 'g1'),
      ses('s06', '2026-06-02T08:00:00Z', 'g1'),
      ses('sDel', '2026-06-03T08:00:00Z', 'g1', true, true),
      ses('s11', '2026-06-06T08:00:00Z', 'g1'),
      ses('s07', '2026-06-08T07:00:00Z', 'g1'),
      ses('s08', '2026-06-09T18:00:00Z', null, false),
      ses('s09', '2026-06-09T18:00:00Z', 'g2'),
    ],
    loggedExercises: [
      le('l01p', 's01', 'ex-press', 0), le('l01s', 's01', 'ex-squat', 1),
      le('l02p', 's02', 'ex-press', 0), le('l02r', 's02', 'ex-row', 1),
      le('l03p', 's03', 'ex-press', 0), le('l03s', 's03', 'ex-squat', 1),
      le('l10p', 's10', 'ex-press', 0, true), le('l10s', 's10', 'ex-squat', 1),
      le('l04p', 's04', 'ex-press', 0), le('l04c', 's04', 'ex-curl', 1),
      le('l05p', 's05', 'ex-press', 0), le('l05s', 's05', 'ex-squat', 1), le('l05r', 's05', 'ex-row', 2),
      le('l06p', 's06', 'ex-press', 0), le('l06c', 's06', 'ex-curl', 1), le('l06g', 's06', 'ex-ghost', 2),
      le('lDelp', 'sDel', 'ex-press', 0),
      // A logged exercise without sets still counts as training its group (and adds a 0 to its volume).
      le('l11g', 's11', 'ex-calf', 0),
      le('l07s', 's07', 'ex-squat', 0), le('l07c', 's07', 'ex-curl', 1), le('l07r', 's07', 'ex-row', 2),
      le('l08p', 's08', 'ex-press', 0),
      le('l09p', 's09', 'ex-press', 0),
    ],
    sets: [
      set('t01a', 'l01p', 0, 100, 5), set('t01b', 'l01s', 0, 100, 5),
      set('t02a', 'l02p', 0, 100, 5), set('t02b', 'l02r', 0, 60, 10),
      set('t03a', 'l03p', 0, 95, 6), set('t03b', 'l03s', 0, 105, 5),
      set('t10a', 'l10p', 0, 140, 3), set('t10b', 'l10s', 0, 100, 5), set('t10c', 'l10s', 1, 300, 1, { deleted: true }),
      set('t04a', 'l04p', 0, 100, 4), set('t04b', 'l04c', 0, 20, 10),
      set('t05a', 'l05p', 0, 100, 5), set('t05b', 'l05s', 0, 110, 5),
      set('t05c', 'l05r', 0, 40, 12, { warmup: true }), set('t05d', 'l05r', 1, 60, 10),
      set('t06a', 'l06p', 0, 90, 8), set('t06b', 'l06c', 0, 20, 8), set('t06c', 'l06g', 0, 10, 10),
      set('tDel', 'lDelp', 0, 150, 5),
      set('t07a', 'l07s', 0, 112.5, 5), set('t07b', 'l07c', 0, 20, 11),
      set('t07c', 'l07r', 0, 70, 3, { warmup: true }), set('t07d', 'l07r', 1, 60, 10),
      set('t08a', 'l08p', 0, 80, 10), set('t08b', 'l08p', 1, 200, 1, { deleted: true }),
      set('t09a', 'l09p', 0, 85, 8),
    ],
  };
}

/** Sessions around the Sunday/Monday and day boundaries, evaluated in several zones at the same instant. */
function boundaryDataset() {
  return {
    sessions: [
      ses('b1', '2026-06-07T12:00:00Z', null),
      ses('b2', '2026-06-13T23:30:00Z', null),
      ses('b3', '2026-06-14T21:30:00Z', null),
      ses('b4', '2026-06-14T22:30:00Z', null),
      ses('b5', '2026-06-15T00:05:00Z', null),
    ],
    loggedExercises: [
      le('lb1', 'b1', 'ex-press'), le('lb2', 'b2', 'ex-press'), le('lb3', 'b3', 'ex-press'),
      le('lb4', 'b4', 'ex-squat'), le('lb5', 'b5', 'ex-press'),
    ],
    sets: [
      set('tb1', 'lb1', 0, 80, 5), set('tb2', 'lb2', 0, 82.5, 5), set('tb3', 'lb3', 0, 85, 3),
      set('tb4', 'lb4', 0, 100, 5), set('tb5', 'lb5', 0, 85, 5),
    ],
  };
}

/**
 * Sessions on both sides of a DST change, evaluated in that zone the day after: Europe/Madrid springs forward on
 * Sun 2026-03-29 (02:00 CET -> 03:00 CEST), America/New_York falls back on Sun 2026-11-01 (02:00 EDT -> 01:00 EST).
 * Weeks (local Monday), local days for the day streak and 24 h-based periods all cross the change.
 */
function dstDataset(sessions) {
  return {
    sessions: sessions.map(([id, at]) => ses(id, at, null)),
    loggedExercises: sessions.map(([id], i) => le(`l${id}`, id, i % 2 === 0 ? 'ex-press' : 'ex-squat')),
    sets: sessions.map(([id], i) => set(`t${id}`, `l${id}`, 0, 80 + 2.5 * i, 5)),
  };
}

async function seed(dataset) {
  await Promise.all([db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(), db.exercises.clear()]);
  await db.exercises.bulkPut(dataset.exercises.map((e) => ({ ...e, userId: null, equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: e.id === 'ex-row', updatedAt: 1 })));
  await db.workoutSessions.bulkPut(dataset.sessions.map(({ duracionSegundos, ...s }) => ({
    ...s, userId: null, routineId: null, updatedAt: 1, ...(duracionSegundos == null ? {} : { duracionSegundos }),
  })));
  await db.loggedExercises.bulkPut(dataset.loggedExercises.map((x) => ({ ...x, updatedAt: 1 })));
  await db.loggedSets.bulkPut(dataset.sets.map((x) => ({ ...x, updatedAt: 1 })));
}

const PERIODS = ['4s', '3m', 'todo'];

async function evaluate(dataset, gyms, objetivos) {
  await seed(dataset);
  const now = dataset.now;
  const exerciseIds = [...new Set(dataset.loggedExercises.map((x) => x.exerciseId))].sort();
  const byGym = [];
  for (const gymId of gyms) {
    const g = gymId ?? undefined;
    const lastTrained = await getLastTrainedByMuscle(g);
    const volumenSemana = await getVolumenSemanaByMuscle(g, now);
    const periods = [];
    for (const periodo of PERIODS) {
      const since = periodoASinceTs(periodo, now);
      const weeklyVolume = await getWeeklyVolume(since, g);
      const volumeByMuscle = await getVolumeByMuscle(since, g);
      periods.push({
        periodo, since,
        periodSummary: await getPeriodSummary(since, g),
        weeklyVolume: weeklyVolumeDeltas(weeklyVolume),
        volumeByMuscle,
        balance: filasEquilibrio({ data: volumeByMuscle, lastTrained, ahora: now, objetivos, volumenSemana }),
      });
    }
    const exercises = [];
    for (const exerciseId of exerciseIds) {
      const progress = [];
      for (const periodo of PERIODS) progress.push({ periodo, points: await getExerciseProgress(exerciseId, g, periodoASinceTs(periodo, now)) });
      exercises.push({ exerciseId, prs: await getExercisePRs(exerciseId, g), progress });
    }
    byGym.push({
      gymId,
      weeklySummary: await getWeeklySummary(g, now),
      prsThisWeek: await getPRsThisWeek(g, now),
      volumenSemanaByMuscle: volumenSemana,
      lastTrainedByMuscle: lastTrained,
      estancados: await listEstancados(g),
      periods,
      exercises,
    });
  }
  const racha = [];
  for (const objetivo of [1, 2, 3]) racha.push({ objetivo, ...(await getRachaSemanal(objetivo, now)) });
  return { byGym, currentStreakDays: await getCurrentStreakDays(now), racha };
}

async function datasetCases() {
  const objetivos = { pecho: 2000, gemelo: 500, espalda: 0 };
  const empty = { sessions: [], loggedExercises: [], sets: [] };
  const oneSet = { sessions: [ses('o1', '2026-06-09T18:00:00Z', 'g1')], loggedExercises: [le('lo1', 'o1', 'ex-press')], sets: [set('to1', 'lo1', 0, 60, 8)] };
  const specs = [
    { name: 'empty', zone: 'Europe/Madrid', now: '2026-06-09T20:00:00Z', data: empty, gyms: [null, 'g1'] },
    { name: 'one set', zone: 'Europe/Madrid', now: '2026-06-09T20:00:00Z', data: oneSet, gyms: [null, 'g1', 'g2'] },
    { name: 'history', zone: 'Europe/Madrid', now: '2026-06-09T20:00:00Z', data: historyDataset(), gyms: [null, 'g1', 'g2'] },
    {
      name: 'dst spring @ Europe/Madrid', zone: 'Europe/Madrid', now: '2026-03-30T10:00:00Z', gyms: [null],
      data: dstDataset([
        ['m1', '2026-03-22T12:00:00Z'], ['m2', '2026-03-28T22:30:00Z'], ['m3', '2026-03-29T00:30:00Z'],
        ['m4', '2026-03-29T01:30:00Z'], ['m5', '2026-03-29T21:30:00Z'], ['m6', '2026-03-29T22:30:00Z'], ['m7', '2026-03-30T08:00:00Z'],
      ]),
    },
    {
      name: 'dst fall @ America/New_York', zone: 'America/New_York', now: '2026-11-02T15:00:00Z', gyms: [null],
      data: dstDataset([
        ['n1', '2026-10-25T14:00:00Z'], ['n2', '2026-11-01T03:30:00Z'], ['n3', '2026-11-01T05:30:00Z'],
        ['n4', '2026-11-01T06:30:00Z'], ['n5', '2026-11-02T04:30:00Z'], ['n6', '2026-11-02T05:30:00Z'], ['n7', '2026-11-02T14:00:00Z'],
      ]),
    },
    ...['UTC', 'Europe/Madrid', 'America/New_York', 'Pacific/Auckland'].map((zone) => (
      { name: `boundary @ ${zone}`, zone, now: '2026-06-15T00:15:00Z', data: boundaryDataset(), gyms: [null] }
    )),
  ];
  const cases = [];
  for (const spec of specs) {
    const dataset = { ...spec.data, exercises: EXERCISES, now: iso(spec.now) };
    const expected = await inZoneAsync(spec.zone, () => evaluate(dataset, spec.gyms, objetivos));
    cases.push({
      name: spec.name, zone: spec.zone, now: dataset.now, objetivosVolumen: objetivos,
      exercises: EXERCISES, sessions: dataset.sessions, loggedExercises: dataset.loggedExercises, sets: dataset.sets,
      expected,
    });
  }
  return cases;
}

function stallCases() {
  const pts = (rms) => rms.map((rm, i) => ({ fecha: 1000 + i, maxPeso: 0, mejor1RM: rm, volumen: 0 }));
  const inputs = [
    [[], 3], [[100], 3], [[100, 100, 100], 3], [[105, 100, 100, 100], 3], [[100, 100, 100, 100], 3],
    [[100, 100, 100, 105], 3], [[100, 100, 110, 108], 3], [[105, 100, 100], 2], [[100, 100, 106], 2],
    [[90, 100, 95, 100, 99, 98], 3], [[100, 101, 100, 101, 100, 100, 100], 3], [[50, 60, 70, 65, 65, 65, 65, 66], 3],
  ];
  return inputs.map(([rms, n]) => ({ mejor1RM: rms, n, expected: detectarEstancamiento(pts(rms), n) }));
}

function weeklyDeltaCases() {
  const inputs = [[], [1000], [1000, 1500], [1000, 0, 500], [0, 0], [3000, 2000, 2010, 1990], [333, 1000, 999.5]];
  return inputs.map((vols) => {
    const points = vols.map((volumen, i) => ({ semanaInicioTs: i * 7 * DAY, volumen }));
    return { volumes: vols, expected: weeklyVolumeDeltas(points).map((p) => p.deltaPct) };
  });
}

function snapshotCases() {
  const zeros = Object.fromEntries(MUSCLE_GROUPS.map((g) => [g, 0]));
  const nulls = Object.fromEntries(MUSCLE_GROUPS.map((g) => [g, null]));
  const ahora = iso('2026-06-10T10:00:00Z');
  const entry = (valor, at) => ({ valor, fecha: iso(at) });
  const inputs = {
    empty: {
      estancados: [], semana: { sesiones: 0, volumenSemana: 0, volumenSemanaPrevia: 0, deltaPct: null }, objetivoSemanal: 3, prs: [],
      volumenSemanaPorGrupo: zeros, lastTrained: nulls, objetivosVolumen: {}, ahora, cuerpo: [],
    },
    full: {
      estancados: ['A', 'B', 'C', 'D', 'E', 'F'].map((n, i) => ({ exerciseId: `e${i}`, nombre: n, sesionesSinMejora: 9 - i, ultimaMejoraFecha: null })),
      semana: { sesiones: 3, volumenSemana: 12345.6, volumenSemanaPrevia: 10000, deltaPct: 23 }, objetivoSemanal: 4,
      prs: [{ exerciseId: 'e1', nombre: 'Press', tipo: 'peso' }, { exerciseId: 'e2', nombre: 'Curl', tipo: '1rm' }],
      volumenSemanaPorGrupo: { ...zeros, pecho: 3000.4, espalda: 4000.5, hombros: 100, biceps: 900, triceps: 800, cuadriceps: 5000, femoral: 50, gluteo: 60, gemelo: 70, abdomen: 80 },
      lastTrained: { ...nulls, pecho: iso('2026-06-08T10:00:00Z'), espalda: iso('2026-05-20T09:00:00Z'), antebrazo: iso('2026-01-01T00:00:00Z') },
      objetivosVolumen: { otro: 1000, pecho: 2000 },
      ahora,
      cuerpo: [
        { tipo: 'peso', label: 'Peso', entradas: [entry(82, '2026-04-01T08:00:00Z'), entry(80.5, '2026-05-20T08:00:00Z'), entry(79.2, '2026-06-09T08:00:00Z')] },
        { tipo: 'cintura', label: 'Cintura', entradas: [entry(90, '2026-06-01T08:00:00Z')] },
        { tipo: 'pecho', label: 'Pecho', entradas: [entry(100, '2026-03-01T08:00:00Z'), entry(101, '2026-05-01T08:00:00Z')] },
        { tipo: 'brazo', label: 'Brazo', entradas: [entry(35, '2026-05-15T08:00:00Z'), entry(35.5, '2026-06-05T08:00:00Z')] },
        { tipo: 'muslo', label: 'Muslo', entradas: [entry(60, '2026-06-02T08:00:00Z')] },
        { tipo: 'cadera', label: 'Cadera', entradas: [entry(95, '2026-06-03T08:00:00Z')] },
        { tipo: 'cuello', label: 'Cuello', entradas: [entry(38, '2026-06-04T08:00:00Z')] },
        { tipo: 'gemelo', label: 'Gemelo', entradas: [entry(37, '2026-02-04T08:00:00Z')] },
        { tipo: 'vacio', label: 'Vacío', entradas: [] },
      ],
    },
    weightOnlyOld: {
      estancados: [], semana: { sesiones: 1, volumenSemana: 0.5, volumenSemanaPrevia: 0, deltaPct: null }, objetivoSemanal: 3, prs: [],
      volumenSemanaPorGrupo: zeros, lastTrained: nulls, objetivosVolumen: { gemelo: 300 }, ahora,
      cuerpo: [{ tipo: 'peso', label: 'Peso', entradas: [entry(70, '2026-01-01T08:00:00Z'), entry(71, '2026-02-01T08:00:00Z')] }],
    },
  };
  return Object.entries(inputs).map(([name, input]) => ({ name, input, expected: construirSnapshot(input) }));
}

async function insightCases() {
  return {
    description: 'Cases for the PWA progress statistics (lib/repositories/stats.ts run on a seeded Dexie database, lib/insights.ts, '
      + "lib/gym-filter.ts, lib/coach-snapshot.ts) and their Android port (core:domain stats/WorkoutStats.kt, stats/Insights.kt). "
      + "Datasets list every row, tombstones included ('deletedAt' non-null); 'expected' is what the PWA functions return evaluated with the "
      + "process time zone 'zone' and clock 'now' (epoch ms). Warm-up sets (esCalentamiento) count in EVERY metric, as in the PWA. "
      + "byGym[].gymId null = all gyms. Volumes are float sums: compare with a small tolerance.",
    deloadAdvice: DELOAD_CONSEJO,
    gymFilter: ['all', 'g1', ''].map((filter) => ({ filter, gymId: filtroAGymId(filter) ?? null })),
    datasets: await datasetCases(),
    stalls: stallCases(),
    weeklyVolumeDeltas: weeklyDeltaCases(),
    coachSnapshot: snapshotCases(),
  };
}

// ---- achievement-cases.json: lib/logros.ts, lib/reconciliar-logros.ts, getLogroMetricas/listPRs (lib/repositories/stats.ts) ----

const WEEK = 7 * DAY;
const CANONICAL_PREFIX = 'ach:';

/**
 * The APPROVED Android reconciliation outcome. The PWA only ever unlocks (reconciliarLogros never revokes and
 * identifies rows by `clave`, minting a random id); Android also tombstones an achievement whose evidence is gone
 * after a history edit and revives it when re-earned. Per catalog key:
 * - earned and a live row exists -> retain (no write; duplicates from other devices are all kept);
 * - earned, no live row -> unlock: revive the row with the canonical id `ach:<clave>` if present, else the
 *   tombstoned row with the lowest id, else create `ach:<clave>` (stable logical identity per clave, so devices converge);
 * - not earned, live rows -> tombstone every live row; not earned and none live -> none.
 * Keys outside the catalog are never touched.
 */
function androidReconcile(met, stored) {
  const decisions = {};
  const after = stored.map((row) => ({ ...row }));
  for (const def of LOGROS_DEF) {
    const rows = after.filter((row) => row.key === def.clave);
    const live = rows.filter((row) => row.state === 'live');
    if (met.includes(def.clave)) {
      if (live.length > 0) { decisions[def.clave] = 'retain'; continue; }
      decisions[def.clave] = 'unlock';
      const canonicalId = CANONICAL_PREFIX + def.clave;
      const target = rows.find((row) => row.id === canonicalId) ?? [...rows].sort((a, b) => (a.id < b.id ? -1 : 1))[0];
      if (target) target.state = 'live';
      else after.push({ id: canonicalId, key: def.clave, state: 'live' });
    } else if (live.length > 0) {
      decisions[def.clave] = 'tombstone';
      live.forEach((row) => { row.state = 'tombstoned'; });
    } else {
      decisions[def.clave] = 'none';
    }
  }
  after.sort((a, b) => (a.id < b.id ? -1 : 1));
  return { decisions, stored: after };
}

async function seedAchievementData(data) {
  await Promise.all([db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(), db.exercises.clear(), db.mesocycles.clear(), db.achievements.clear()]);
  await seed({ ...data, exercises: EXERCISES });
  await db.mesocycles.bulkPut(data.mesocycles.map((m) => ({
    id: m.id, userId: null, nombre: 'Meso', objetivo: 'fuerza', semanas: m.semanas, diasPorSemana: 3, notas: null, progresion: [],
    fechaInicio: m.fechaInicio, updatedAt: 1, deletedAt: m.deleted ? DELETED : null,
  })));
}

/** [id-prefix, count, first ISO, step days, sets per session [peso, reps]] -> live sessions, one exercise each. */
function trainingBlock(prefix, count, firstIso, stepDays, exerciseId, peso, reps, setCount = 1) {
  const data = { sessions: [], loggedExercises: [], sets: [] };
  for (let i = 0; i < count; i++) {
    const id = `${prefix}${String(i).padStart(3, '0')}`;
    data.sessions.push(ses(id, new Date(iso(firstIso) + i * stepDays * DAY).toISOString(), null));
    data.loggedExercises.push(le(`l${id}`, id, exerciseId));
    for (let k = 0; k < setCount; k++) data.sets.push(set(`t${id}-${k}`, `l${id}`, k, peso, reps));
  }
  return data;
}

const merge = (...parts) => ({
  sessions: parts.flatMap((p) => p.sessions ?? []),
  loggedExercises: parts.flatMap((p) => p.loggedExercises ?? []),
  sets: parts.flatMap((p) => p.sets ?? []),
  mesocycles: parts.flatMap((p) => p.mesocycles ?? []),
});
/** Deleting a session tombstones its logged exercises and sets too, exactly like the PWA's softDeleteSession. */
function without(data, predicate) {
  const gone = new Set(data.sessions.filter(predicate).map((s) => s.id));
  const goneExercises = new Set(data.loggedExercises.filter((x) => gone.has(x.sessionId)).map((x) => x.id));
  const mark = (row) => ({ ...row, deletedAt: DELETED });
  return {
    ...data,
    sessions: data.sessions.map((s) => (gone.has(s.id) ? mark(s) : s)),
    loggedExercises: data.loggedExercises.map((x) => (gone.has(x.sessionId) ? mark(x) : x)),
    sets: data.sets.map((x) => (goneExercises.has(x.loggedExerciseId) ? mark(x) : x)),
  };
}

async function achievementScenarios() {
  const NOW = iso('2026-06-09T20:00:00Z');
  const press = 'ex-press';
  // 12 consecutive training weeks (Mondays 2026-03-23 .. 2026-06-08, one session a week, Madrid), 1 exercise.
  const weekly12 = trainingBlock('w', 12, '2026-03-23T08:00:00Z', 7, press, 100, 5);
  const weekly12WithoutMiddle = without(weekly12, (s) => s.id === 'w005');
  const tenSessions = trainingBlock('d', 10, '2026-05-01T08:00:00Z', 1, press, 80, 5);
  const nineSessions = without(tenSessions, (s) => s.id === 'd009');
  const heavy = (peso) => trainingBlock('h', 5, '2026-05-01T08:00:00Z', 2, 'ex-squat', peso, 1);
  const specs = [
    {
      name: 'sessions: edit removes then restores a session', zone: 'Europe/Madrid', objetivo: 3, initialStored: [],
      steps: [
        { label: '10 sessions earns sessions-10', now: NOW, data: merge(tenSessions) },
        { label: 'unchanged: nothing to write', now: NOW, data: merge(tenSessions) },
        { label: 'history edit deletes one session: sessions-10 is tombstoned', now: NOW, data: merge(nineSessions) },
        { label: 'still 9: nothing to write', now: NOW, data: merge(nineSessions) },
        { label: 'the session is restored: sessions-10 is re-earned on the same row', now: NOW, data: merge(tenSessions) },
      ],
    },
    {
      name: 'streak: a missing week breaks the run, restoring it re-earns', zone: 'Europe/Madrid', objetivo: 1, initialStored: [],
      steps: [
        { label: '12 weeks in a row earn every streak and 10 sessions', now: NOW, data: merge(weekly12) },
        { label: 'middle week emptied: best run is 6, racha-8 and racha-12 go', now: NOW, data: merge(weekly12WithoutMiddle) },
        { label: 'week back: all earned again', now: NOW, data: merge(weekly12) },
      ],
    },
    {
      name: 'volume boundaries are inclusive', zone: 'UTC', objetivo: 3, initialStored: [],
      steps: [
        { label: '500000 exactly earns 100k and 500k', now: NOW, data: merge(heavy(100000)) },
        { label: 'one kg less than 100k tombstones 100k and 500k', now: NOW, data: merge(trainingBlock('h', 1, '2026-05-01T08:00:00Z', 2, 'ex-squat', 99999, 1)) },
        { label: '1,000,000 earns all three volume milestones', now: NOW, data: merge(heavy(200000)) },
      ],
    },
    {
      name: 'mesocycle: completion is strict and tombstones follow deletion', zone: 'Europe/Madrid', objetivo: 3, initialStored: [],
      steps: [
        { label: 'ends exactly now: not completed', now: NOW, data: merge({ mesocycles: [{ id: 'm1', semanas: 4, fechaInicio: NOW - 4 * WEEK }] }) },
        { label: 'one ms later: completed', now: NOW + 1, data: merge({ mesocycles: [{ id: 'm1', semanas: 4, fechaInicio: NOW - 4 * WEEK }] }) },
        { label: 'mesocycle deleted: planner achievement is tombstoned', now: NOW + 1, data: merge({ mesocycles: [{ id: 'm1', semanas: 4, fechaInicio: NOW - 4 * WEEK, deleted: true }] }) },
      ],
    },
    {
      name: 'identity: PWA rows, duplicates, tombstones and unknown keys', zone: 'Europe/Madrid', objetivo: 3,
      initialStored: [
        { id: 'pwa-uuid-1', key: 'sesiones-10', state: 'live' },
        { id: 'pwa-uuid-2', key: 'sesiones-10', state: 'live' },
        { id: 'pwa-uuid-3', key: 'sesiones-50', state: 'tombstoned' },
        { id: 'zz-old', key: 'mesociclo-1', state: 'tombstoned' },
        { id: 'aa-old', key: 'mesociclo-1', state: 'tombstoned' },
        { id: 'future-row', key: 'logro-del-futuro', state: 'live' },
        { id: 'ach:sesiones-100', key: 'sesiones-100', state: 'tombstoned' },
        { id: 'pwa-uuid-9', key: 'sesiones-100', state: 'tombstoned' },
      ],
      steps: [
        {
          label: 'duplicate live rows are retained; tombstoned rows are revived (canonical id first, else lowest id)', now: NOW + 1,
          data: merge(trainingBlock('p', 120, '2026-01-01T08:00:00Z', 1, press, 60, 8), { mesocycles: [{ id: 'm1', semanas: 1, fechaInicio: NOW - 2 * WEEK }] }),
        },
        {
          label: 'evidence gone: every live duplicate is tombstoned, unknown keys stay', now: NOW + 1,
          data: merge({ sessions: [], loggedExercises: [], sets: [], mesocycles: [] }),
        },
      ],
    },
  ];
  const scenarios = [];
  for (const spec of specs) {
    let stored = spec.initialStored;
    const steps = [];
    for (const step of spec.steps) {
      const data = { ...step.data, exercises: EXERCISES };
      const record = await inZoneAsync(spec.zone, async () => {
        await seedAchievementData(data);
        const metrics = await getLogroMetricas(spec.objetivo, step.now);
        const met = evaluarLogros(metrics);
        const outcome = androidReconcile(met, stored);
        // What the PWA would do with the same live rows: add the missing ones, never remove.
        await db.achievements.bulkPut(stored.filter((row) => row.state === 'live').map((row) => (
          { id: row.id, userId: null, clave: row.key, fechaDesbloqueo: 1, updatedAt: 1, deletedAt: null }
        )));
        const pwaNewlyUnlocked = await reconciliarLogros(spec.objetivo, step.now);
        return { metrics, met, outcome, pwaNewlyUnlocked, prs: await listPRs() };
      });
      steps.push({
        label: step.label, now: step.now,
        sessions: step.data.sessions, loggedExercises: step.data.loggedExercises, sets: step.data.sets, mesocycles: step.data.mesocycles,
        expected: {
          metrics: { ...record.metrics, volumenTotal: num(record.metrics.volumenTotal) },
          met: record.met,
          pwaNewlyUnlocked: record.pwaNewlyUnlocked,
          decisions: record.outcome.decisions,
          storedAfter: record.outcome.stored,
          personalRecords: record.prs,
        },
      });
      stored = record.outcome.stored;
    }
    scenarios.push({ name: spec.name, zone: spec.zone, weeklyTarget: spec.objetivo, initialStored: spec.initialStored, steps });
  }
  return scenarios;
}

function achievementStreakCases() {
  const cases = [];
  const W = (n) => n * WEEK;
  const synthetic = [
    { weeks: [[1, 3], [2, 4], [3, 3]], objetivo: 3, current: W(3) },
    { weeks: [[1, 3], [2, 3], [3, 1]], objetivo: 3, current: W(3) },
    { weeks: [[1, 3], [2, 3], [3, 1]], objetivo: 3, current: W(4) },
    { weeks: [[1, 3], [2, 2], [3, 3], [4, 3], [5, 3]], objetivo: 3, current: W(5) },
    { weeks: [[1, 1], [3, 1], [4, 1], [6, 1]], objetivo: 1, current: W(7) },
    { weeks: [], objetivo: 3, current: W(5) },
    { weeks: [[1, 0], [2, 0]], objetivo: 0, current: W(2) },
  ];
  for (const { weeks, objetivo, current } of synthetic) {
    const semanas = weeks.map(([n, sesiones]) => ({ inicioTs: W(n), sesiones }));
    cases.push({ name: `synthetic ${JSON.stringify(weeks)} target ${objetivo}`, weeks: semanas, target: objetivo, currentWeekStart: current, expected: calcularRacha(semanas, objetivo, current) });
  }
  // Real Monday-local week starts across DST changes (week start offsets differ from exact multiples of 7 days).
  const real = {
    'Europe/Madrid': ['2026-03-09T00:00:00', '2026-03-16T00:00:00', '2026-03-23T00:00:00', '2026-03-30T00:00:00', '2026-04-06T00:00:00', '2026-10-19T00:00:00', '2026-10-26T00:00:00', '2026-11-02T00:00:00'],
    'America/New_York': ['2026-03-02T00:00:00', '2026-03-09T00:00:00', '2026-03-16T00:00:00', '2026-10-26T00:00:00', '2026-11-02T00:00:00', '2026-11-09T00:00:00'],
  };
  for (const [zone, starts] of Object.entries(real)) {
    const instants = starts.map((s) => inZone(zone, () => new Date(s).getTime()));
    const semanas = instants.map((inicioTs) => ({ inicioTs, sesiones: 3 }));
    for (const lastIndex of [instants.length - 1, 3]) {
      cases.push({
        name: `real weeks @ ${zone}, current week #${lastIndex}`, zone, weeks: semanas, target: 3, currentWeekStart: instants[lastIndex],
        expected: calcularRacha(semanas, 3, instants[lastIndex]),
      });
    }
  }
  return cases;
}

function achievementEvaluateCases() {
  const base = { sesionesTotales: 0, volumenTotal: 0, prsTotales: 0, mejorRacha: 0, mesociclosCompletados: 0 };
  const grid = {
    sesionesTotales: [0, 9, 10, 49, 50, 99, 100, 249, 250, 5000],
    volumenTotal: [0, 99_999.99, 100_000, 499_999, 500_000, 999_999.5, 1_000_000, 2_500_000],
    mejorRacha: [0, 3, 4, 7, 8, 11, 12, 52],
    mesociclosCompletados: [0, 1, 3],
    prsTotales: [0, 1, 40],
  };
  const cases = [];
  for (const [field, values] of Object.entries(grid)) {
    for (const value of values) cases.push({ metrics: { ...base, [field]: value }, expected: evaluarLogros({ ...base, [field]: value }) });
  }
  const combos = [
    { sesionesTotales: 55, volumenTotal: 120_000, mejorRacha: 4, mesociclosCompletados: 1, prsTotales: 7 },
    { sesionesTotales: 300, volumenTotal: 1_200_000, mejorRacha: 12, mesociclosCompletados: 2, prsTotales: 30 },
  ];
  for (const m of combos) cases.push({ metrics: m, expected: evaluarLogros(m) });
  return cases.map((c) => ({ metrics: { ...c.metrics, volumenTotal: num(c.metrics.volumenTotal) }, expected: c.expected }));
}

async function achievementCases() {
  return {
    description: 'Cases for the PWA achievements (lib/logros.ts evaluarLogros/calcularRacha, lib/reconciliar-logros.ts, getLogroMetricas/listPRs in '
      + 'lib/repositories/stats.ts run on a seeded Dexie database) and the Android port (core:domain achievement/AchievementRules.kt, '
      + "ReconcileAchievementsUseCase, core:data OfflineAchievementRepository). 'catalog', 'evaluate', 'streak' and every scenario's 'metrics', 'met', "
      + "'pwaNewlyUnlocked' and 'personalRecords' are what the TypeScript code returns. 'decisions' and 'storedAfter' are the APPROVED ANDROID outcome "
      + '(see androidReconcile in scripts/export-domain-fixtures.mjs): the PWA never revokes an achievement, Android tombstones one whose evidence a '
      + 'history edit removed and revives it (same row) when re-earned. Steps of a scenario run in order against the same achievement rows; a step '
      + "replaces the workout data (an edit) and is reconciled twice (the second run must write nothing). Rows with deletedAt are 'tombstoned'. "
      + 'Warm-up sets count, as in the PWA. volumenTotal travels as a number (finite in every case).',
    canonicalIdPrefix: CANONICAL_PREFIX,
    exercises: EXERCISES,
    catalog: LOGROS_DEF.map(({ clave, titulo, descripcion }) => ({ clave, titulo, descripcion })),
    evaluate: achievementEvaluateCases(),
    streak: achievementStreakCases(),
    scenarios: await achievementScenarios(),
  };
}

export async function buildFixtures() {
  return {
    'period-cases.json': periodCases(),
    'fecha-cases.json': fechaCases(),
    'num-cases.json': numCases(),
    'body-stats-cases.json': bodyStatsCases(),
    'insight-cases.json': await insightCases(),
    'achievement-cases.json': await achievementCases(),
  };
}

export const OUTPUT_DIR = fileURLToPath(new URL('../contracts/domain/', import.meta.url));
export const serialize = (value) => JSON.stringify(value, null, 2) + '\n';

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  for (const [file, content] of Object.entries(await buildFixtures())) writeFileSync(OUTPUT_DIR + file, serialize(content));
  await db.delete();
  console.log(`Wrote domain fixtures to ${OUTPUT_DIR}`);
}
