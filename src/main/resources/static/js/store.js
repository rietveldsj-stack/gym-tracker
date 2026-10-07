import { uuid } from './util.js';

// The screen shows view() = the last server snapshot with pending changes applied on top. A change the server
// accepted moves from the outbox to `acked` and stays applied until a snapshot fetched after it arrives, so it
// never blinks out of view between being sent and the refresh (or when the signal drops in between).
const SNAPSHOT_KEY = 'gt.snapshot.v1';
const OUTBOX_KEY = 'gt.outbox.v1';
const ACKED_KEY = 'gt.acked.v1';
const EMPTY = { name: null, exercises: [], activeSession: null, history: [], weekly: null, weeklyAsOf: null, exerciseStats: {}, sessionDetails: {} };

function load(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function save(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Storage blocked or full: keep working from memory.
  }
}

let snapshot = { ...EMPTY, ...load(SNAPSHOT_KEY, {}) };
let outbox = load(OUTBOX_KEY, []);
let acked = load(ACKED_KEY, []);
let cachedView = null;
const listeners = new Set();
const dispatchListeners = new Set();

function changed() {
  cachedView = null;
  listeners.forEach((fn) => fn());
}

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function onDispatch(fn) {
  dispatchListeners.add(fn);
}

export function dispatch(kind, payload) {
  outbox.push({ opId: uuid(), kind, payload });
  save(OUTBOX_KEY, outbox);
  changed();
  dispatchListeners.forEach((fn) => fn());
}

export function pending() {
  return outbox.length;
}

export function peek() {
  return outbox[0];
}

/** The server accepted the oldest pending change. */
export function shift() {
  const op = outbox.shift();
  if (op) acked.push(op);
  save(OUTBOX_KEY, outbox);
  save(ACKED_KEY, acked);
  changed();
}

/** The server rejected the oldest pending change: forget it. */
export function drop() {
  outbox.shift();
  save(OUTBOX_KEY, outbox);
  changed();
}

export function ackedIds() {
  return acked.map((op) => op.opId);
}

/** Fresh server data, fetched after the changes in `ackedOpIds` were accepted, so those are now part of it. */
export function applyServerSnapshot(patch, ackedOpIds) {
  acked = acked.filter((op) => !ackedOpIds.includes(op.opId));
  save(ACKED_KEY, acked);
  setSnapshot(patch);
}

/** Forgets everything stored for the account: the snapshot and the changes not sent yet. */
export function reset() {
  snapshot = { ...EMPTY };
  outbox = [];
  acked = [];
  save(SNAPSHOT_KEY, snapshot);
  save(OUTBOX_KEY, outbox);
  save(ACKED_KEY, acked);
  changed();
}

export function setSnapshot(patch) {
  snapshot = { ...snapshot, ...patch };
  save(SNAPSHOT_KEY, snapshot);
  changed();
}

export function cacheSessionDetail(id, detail) {
  setSnapshot({ sessionDetails: { ...snapshot.sessionDetails, [id]: detail } });
}

export function cacheExerciseStats(id, stats) {
  setSnapshot({ exerciseStats: { ...snapshot.exerciseStats, [id]: stats } });
}

export function view() {
  if (!cachedView) cachedView = applyOps(structuredClone(snapshot), [...acked, ...outbox]);
  return cachedView;
}

const byTime = (a, b) => Date.parse(a.loggedAt) - Date.parse(b.loggedAt);
const byName = (a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });

export function summarize(session, endedAt) {
  return {
    id: session.id,
    date: session.date,
    startedAt: session.startedAt,
    endedAt,
    durationSeconds: Math.round((Date.parse(endedAt) - Date.parse(session.startedAt)) / 1000),
    setCount: session.sets.length,
    muscleGroups: [...new Set([...session.sets].sort(byTime).map((s) => s.muscleGroup).filter(Boolean))],
  };
}

/** Applies pending operations to a copy of the snapshot. Mirrors what the server will do with them. */
export function applyOps(state, ops) {
  for (const { kind, payload: p } of ops) {
    switch (kind) {
      case 'exercise.put': {
        const existing = state.exercises.find((e) => e.id === p.id);
        if (existing) Object.assign(existing, { name: p.name, muscleGroup: p.muscleGroup });
        else state.exercises.push({ id: p.id, name: p.name, muscleGroup: p.muscleGroup, lastTime: null, records: { heaviest: null, repRecords: [] } });
        state.exercises.sort(byName);
        break;
      }
      case 'exercise.delete':
        state.exercises = state.exercises.filter((e) => e.id !== p.id);
        break;
      case 'session.start':
        if (!state.activeSession) state.activeSession = { id: p.id, date: p.date, startedAt: p.startedAt, endedAt: null, sets: [] };
        break;
      case 'set.put': {
        const session = state.activeSession;
        if (!session || session.id !== p.sessionId) break;
        const exercise = state.exercises.find((e) => e.id === p.exerciseId);
        const previous = session.sets.find((s) => s.id === p.id);
        const set = {
          ...p,
          exerciseName: exercise?.name ?? previous?.exerciseName ?? '',
          muscleGroup: exercise?.muscleGroup ?? previous?.muscleGroup ?? null,
        };
        if (previous) Object.assign(previous, set);
        else session.sets.push(set);
        session.sets.sort(byTime);
        break;
      }
      case 'set.delete':
        if (state.activeSession) state.activeSession.sets = state.activeSession.sets.filter((s) => s.id !== p.id);
        break;
      case 'session.end': {
        const session = state.activeSession;
        if (!session || session.id !== p.id) break;
        state.activeSession = null;
        if (session.sets.length) state.history.unshift(summarize(session, p.endedAt));
        break;
      }
      case 'session.discard':
        if (state.activeSession?.id === p.id) state.activeSession = null;
        state.history = state.history.filter((h) => h.id !== p.id);
        break;
      case 'name.put':
        state.name = p.name;
        break;
      default:
        break;
    }
  }
  return state;
}
