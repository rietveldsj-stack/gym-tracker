import { request } from './api.js';
import * as store from './store.js';
import { localDateIso } from './util.js';

const ROUTES = {
  'exercise.put': (p) => ['PUT', `/api/exercises/${p.id}`, { name: p.name, muscleGroup: p.muscleGroup }],
  'exercise.delete': (p) => ['DELETE', `/api/exercises/${p.id}`],
  'session.start': (p) => ['PUT', `/api/sessions/${p.id}`, { date: p.date, startedAt: p.startedAt }],
  'session.end': (p) => ['POST', `/api/sessions/${p.id}/end`, { endedAt: p.endedAt }],
  'session.discard': (p) => ['DELETE', `/api/sessions/${p.id}`],
  'set.put': ({ id, ...body }) => ['PUT', `/api/sets/${id}`, body],
  'set.delete': (p) => ['DELETE', `/api/sets/${p.id}`],
};

let running = false;
let again = false;
let unauthorizedHandler = () => {};
let rejectedHandler = () => {};

export function onUnauthorized(fn) {
  unauthorizedHandler = fn;
}

export function onRejected(fn) {
  rejectedHandler = fn;
}

function setState(state) {
  document.body.dataset.sync = state;
}

function signedOut() {
  unauthorizedHandler();
  return 'signed-out';
}

async function send(op) {
  const [method, path, body] = ROUTES[op.kind](op.payload);
  let result = await request(method, path, body);
  if (result.kind === 'forbidden') {
    // Missing or stale CSRF cookie (iOS may drop cookies when the app is killed): fetch a fresh one, retry once.
    await request('GET', '/api/me');
    result = await request(method, path, body);
    if (result.kind === 'forbidden') return { kind: 'network' }; // keep the change and try again later
  }
  return result;
}

async function refresh() {
  const ackedBefore = store.ackedIds();
  const results = {};
  const sources = [
    ['exercises', '/api/exercises'],
    ['activeSession', '/api/sessions/active'],
    ['history', '/api/sessions'],
    ['weekly', `/api/stats/weekly?weeks=12&today=${localDateIso()}`],
  ];
  for (const [key, path] of sources) {
    const result = await request('GET', path);
    if (result.kind === 'unauthorized') return signedOut();
    if (result.kind !== 'ok') return 'offline';
    results[key] = result.data;
  }
  store.applyServerSnapshot({ ...results, weeklyAsOf: new Date().toISOString() }, ackedBefore);
  return 'idle';
}

async function flushOnce() {
  // Always first and on its own: refreshes the CSRF cookie and lets a remember-me login finish before other
  // requests. Parallel remember-me logins would trip Spring's cookie-theft check and sign her out.
  const me = await request('GET', '/api/me');
  if (me.kind === 'unauthorized') return signedOut();
  if (me.kind !== 'ok') return 'offline';
  while (store.pending() > 0) {
    const result = await send(store.peek());
    if (result.kind === 'ok') {
      store.shift();
    } else if (result.kind === 'unauthorized') {
      return signedOut();
    } else if (result.kind === 'rejected') {
      store.drop();
      rejectedHandler(result.message);
    } else {
      return 'offline';
    }
  }
  return refresh();
}

/** Sends pending changes in order, then reloads the snapshot. Safe to call any time. */
export async function flush() {
  if (running) {
    again = true;
    return;
  }
  running = true;
  setState('syncing');
  let outcome = 'idle';
  try {
    do {
      again = false;
      outcome = await flushOnce();
    } while (again && outcome === 'idle');
  } finally {
    running = false;
    setState(outcome);
  }
}

export function startSync() {
  store.onDispatch(() => flush());
  window.addEventListener('online', () => flush());
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') flush();
  });
  setInterval(() => {
    if (store.pending() > 0) flush();
  }, 15000);
}
