// Exports the shared PWA <-> Android domain parity fixtures (contracts/domain/*-cases.json) by running the
// TypeScript implementation. Inputs are listed here; expected values are whatever the TS code returns, and
// are reviewed by hand in the diff. Deterministic: fixed inputs, explicit time zones, 2-space JSON.
// Usage: npm run export:domain-fixtures   (tsx loads the TypeScript sources)
// The TS tests (lib/*.test.ts) run these same files against the TS code, and Android's DomainParityTest
// runs them against the Kotlin port. progression.json / routine-rotation.json are older, hand-maintained.
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { periodoASinceTs, inicioSemana } from '../lib/period.ts';
import { formatHaceDias, formatSegundos } from '../lib/fecha.ts';
import { parseEnteroOpt, parseEntero, parseDecimal } from '../lib/num.ts';
import { resumenSerie } from '../lib/body-stats.ts';
import { estimar1RM } from '../lib/repositories/stats.ts';

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

export function buildFixtures() {
  return {
    'period-cases.json': periodCases(),
    'fecha-cases.json': fechaCases(),
    'num-cases.json': numCases(),
    'body-stats-cases.json': bodyStatsCases(),
  };
}

export const OUTPUT_DIR = fileURLToPath(new URL('../contracts/domain/', import.meta.url));
export const serialize = (value) => JSON.stringify(value, null, 2) + '\n';

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  for (const [file, content] of Object.entries(buildFixtures())) writeFileSync(OUTPUT_DIR + file, serialize(content));
  console.log(`Wrote domain fixtures to ${OUTPUT_DIR}`);
}
