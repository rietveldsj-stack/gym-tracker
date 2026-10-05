import * as store from '../store.js';
import { navigate } from '../router.js';
import { openSheet, confirmDialog, toast } from '../ui.js';
import { esc, uuid, localDateIso } from '../util.js';
import {
  MUSCLE_GROUPS, muscleLabel, formatSet, formatSetCount, formatClock, formatDuration, formatDate, formatLongDate, parseWeight,
} from '../format.js';
import { openExerciseForm } from './exercises.js';
import { prFlags, isPr, recordsByExercise } from '../records.js';

let timer = null;
const byTime = (a, b) => Date.parse(a.loggedAt) - Date.parse(b.loggedAt);

export function render(container, route) {
  clearInterval(timer);
  if (route.summary) {
    renderSummary(container, route.summary);
    return;
  }
  const session = store.view().activeSession;
  if (session) renderSession(container, session);
  else renderStart(container);
}

function renderStart(container) {
  const last = store.view().history[0];
  container.innerHTML = `
    <section class="screen">
      <header class="topbar"><h1>Workout</h1></header>
      <p class="muted">${formatLongDate(localDateIso())}</p>
      <button class="btn primary huge" data-action="start">Start session</button>
      ${last ? `
        <div class="card">
          <h2>Last workout</h2>
          <p>${formatDate(last.date)} · ${formatDuration(last.durationSeconds)} · ${formatSetCount(last.setCount)}</p>
        </div>` : ''}
    </section>`;
  container.querySelector('[data-action="start"]').addEventListener('click', () => {
    store.dispatch('session.start', { id: uuid(), date: localDateIso(), startedAt: new Date().toISOString() });
  });
}

/** Groups sets by exercise, in the order each exercise was first done. */
export function groupSets(sets) {
  const groups = new Map();
  for (const set of [...sets].sort(byTime)) {
    if (!groups.has(set.exerciseId)) groups.set(set.exerciseId, { exerciseId: set.exerciseId, name: set.exerciseName, sets: [] });
    groups.get(set.exerciseId).sets.push(set);
  }
  return [...groups.values()];
}

function elapsed(session) {
  return formatClock((Date.now() - Date.parse(session.startedAt)) / 1000);
}

function setRowExtras(set, flags) {
  const warmup = set.type === 'WARMUP' ? '<span class="tag">Warmup</span>' : '';
  const pr = isPr(flags.get(set.id)) ? '<span class="pr" aria-label="Personal record">🏆</span>' : '';
  return warmup + pr;
}

function sessionFlags(sets) {
  return prFlags(sets, recordsByExercise(store.view().exercises));
}

function renderSession(container, session) {
  const groups = groupSets(session.sets);
  const flags = sessionFlags(session.sets);
  container.innerHTML = `
    <section class="screen">
      <header class="topbar">
        <div>
          <h1>${formatDate(session.date)}</h1>
          <p class="timer" id="session-timer">${elapsed(session)}</p>
        </div>
        <div class="actions">
          <button class="btn icon ghost" data-action="menu" aria-label="More options">⋯</button>
          <button class="btn primary" data-action="end">End</button>
        </div>
      </header>
      ${groups.map((group) => `
        <div class="card">
          <h2>${esc(group.name)}</h2>
          <ul class="sets">
            ${group.sets.map((set, i) => `
              <li><button class="set-row" data-set="${set.id}">
                <span class="set-no">${i + 1}</span>
                <span>${formatSet(set.weightKg, set.reps)}</span>
                ${setRowExtras(set, flags)}
              </button></li>`).join('')}
          </ul>
          <button class="btn secondary small" data-add-set="${group.exerciseId}">+ Set</button>
        </div>`).join('')}
      ${groups.length ? '' : '<p class="empty">Add an exercise to start logging sets.</p>'}
      <button class="btn primary big" data-action="add-exercise">+ Add exercise</button>
    </section>`;
  timer = setInterval(() => {
    const el = document.getElementById('session-timer');
    if (el) el.textContent = elapsed(session);
  }, 1000);
  container.querySelector('[data-action="end"]').addEventListener('click', () => endSession(session));
  container.querySelector('[data-action="menu"]').addEventListener('click', () => openMenu(session));
  container.querySelector('[data-action="add-exercise"]').addEventListener('click', () => openPicker(session));
  container.querySelectorAll('[data-add-set]').forEach((button) => button.addEventListener('click', () => {
    openSetSheet(session.id, exerciseFor(button.dataset.addSet, session));
  }));
  container.querySelectorAll('[data-set]').forEach((button) => button.addEventListener('click', () => {
    const set = session.sets.find((s) => s.id === button.dataset.set);
    openSetSheet(session.id, exerciseFor(set.exerciseId, session), set);
  }));
}

/** The exercise from the list, or a stand-in built from its sets if it has been deleted since. */
function exerciseFor(exerciseId, session) {
  const exercise = store.view().exercises.find((e) => e.id === exerciseId);
  if (exercise) return exercise;
  const set = session.sets.find((s) => s.exerciseId === exerciseId);
  return { id: exerciseId, name: set?.exerciseName ?? '', muscleGroup: set?.muscleGroup ?? null, lastTime: null, records: null };
}

function openPicker(session) {
  let query = '';
  let unsubscribe = () => {};
  const { el, close } = openSheet(`
    <h2>Add exercise</h2>
    <input class="search" type="search" placeholder="Search" aria-label="Search exercises">
    <button class="btn secondary" data-action="new">+ New exercise</button>
    <div class="picker-list"></div>`, { onClose: () => unsubscribe() });
  const list = el.querySelector('.picker-list');
  const draw = () => {
    const q = query.trim().toLowerCase();
    const all = store.view().exercises;
    const matches = all.filter((e) => e.name.toLowerCase().includes(q));
    list.innerHTML = MUSCLE_GROUPS.map((group) => {
      const items = matches.filter((e) => e.muscleGroup === group);
      return items.length ? `
        <h3 class="group-title">${muscleLabel(group)}</h3>
        <ul class="list">${items.map((e) => `<li><button class="row" data-pick="${e.id}">${esc(e.name)}</button></li>`).join('')}</ul>` : '';
    }).join('') || `<p class="empty">${all.length ? 'No matching exercises.' : 'No exercises yet.'}</p>`;
  };
  draw();
  unsubscribe = store.subscribe(draw); // exercises can arrive while the picker is open (first sync, slow signal)
  el.querySelector('.search').addEventListener('input', (event) => {
    query = event.target.value;
    draw();
  });
  list.addEventListener('click', (event) => {
    const button = event.target.closest('[data-pick]');
    if (!button) return;
    close();
    openSetSheet(session.id, store.view().exercises.find((e) => e.id === button.dataset.pick));
  });
  el.querySelector('[data-action="new"]').addEventListener('click', () => {
    close();
    openExerciseForm(null, { onSaved: (exercise) => openSetSheet(session.id, exercise) });
  });
}

/** Weight and reps to start from: this session's last set of the exercise, else last time, else 0 kg × 10. */
export function prefillFor(exerciseId, sessionSets, lastTime) {
  const inSession = sessionSets.filter((s) => s.exerciseId === exerciseId).sort(byTime).at(-1);
  if (inSession) return { weightKg: Number(inSession.weightKg), reps: inSession.reps };
  if (lastTime) return { weightKg: Number(lastTime.weightKg), reps: lastTime.reps };
  return { weightKg: 0, reps: 10 };
}

const clampWeight = (w) => Math.min(500, Math.max(0, Math.round(w * 4) / 4));
const clampReps = (r) => Math.min(100, Math.max(1, r));

/** Bottom sheet to log a new set (no `set`) or edit an existing one. */
function openSetSheet(sessionId, exercise, set = null) {
  const sessionSets = store.view().activeSession?.sets ?? [];
  const start = set ?? prefillFor(exercise.id, sessionSets, exercise.lastTime);
  let type = set?.type ?? 'WORK';
  const { el, close } = openSheet(`
    <h2>${esc(exercise.name)}</h2>
    ${exercise.lastTime ? `<p class="muted">Last time: ${formatSet(exercise.lastTime.weightKg, exercise.lastTime.reps)}</p>` : ''}
    <div class="stepper" data-field="weight">
      <button class="btn step" data-step="-2.5" aria-label="Decrease weight">−</button>
      <label class="step-value"><input inputmode="decimal" aria-label="Weight in kg" value="${Number(start.weightKg)}"><span>kg</span></label>
      <button class="btn step" data-step="2.5" aria-label="Increase weight">+</button>
    </div>
    <div class="stepper" data-field="reps">
      <button class="btn step" data-step="-1" aria-label="Decrease reps">−</button>
      <label class="step-value"><input inputmode="numeric" aria-label="Reps" value="${start.reps}"><span>reps</span></label>
      <button class="btn step" data-step="1" aria-label="Increase reps">+</button>
    </div>
    <div class="segmented" role="radiogroup" aria-label="Set type">
      <button type="button" role="radio" data-type="WARMUP">Warmup</button>
      <button type="button" role="radio" data-type="WORK">Work</button>
    </div>
    <button class="btn primary big" data-action="save">Save</button>
    ${set ? '<button class="btn danger" data-action="delete">Delete set</button>' : ''}`);
  const weightInput = el.querySelector('[data-field="weight"] input');
  const repsInput = el.querySelector('[data-field="reps"] input');
  const paintType = () => el.querySelectorAll('[data-type]').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.type === type)));
  paintType();
  el.querySelectorAll('[data-type]').forEach((b) => b.addEventListener('click', () => {
    type = b.dataset.type;
    paintType();
  }));
  el.querySelectorAll('[data-step]').forEach((b) => b.addEventListener('click', () => {
    const step = Number(b.dataset.step);
    if (b.closest('[data-field="weight"]')) weightInput.value = clampWeight((parseWeight(weightInput.value) ?? 0) + step);
    else repsInput.value = clampReps((parseInt(repsInput.value, 10) || 0) + step);
  }));
  let saved = false;
  el.querySelector('[data-action="save"]').addEventListener('click', () => {
    if (saved) return; // a double tap must log one set, not two
    const weightKg = parseWeight(weightInput.value);
    const reps = Number(repsInput.value);
    if (weightKg === null) {
      toast('Enter a weight');
      return;
    }
    if (!Number.isInteger(reps) || reps < 1 || reps > 100) {
      toast('Reps must be between 1 and 100');
      return;
    }
    saved = true;
    const id = set?.id ?? uuid();
    store.dispatch('set.put', {
      id, sessionId, exerciseId: exercise.id, weightKg, reps, type, loggedAt: set?.loggedAt ?? new Date().toISOString(),
    });
    close();
    if (isPr(sessionFlags(store.view().activeSession?.sets ?? []).get(id))) toast('🏆 New PR!');
  });
  el.querySelector('[data-action="delete"]')?.addEventListener('click', async () => {
    close();
    if (await confirmDialog('Delete this set?', { ok: 'Delete', danger: true })) store.dispatch('set.delete', { id: set.id });
  });
}

function openMenu(session) {
  const { el, close } = openSheet(`
    <div class="stack">
      <button class="btn danger big" data-action="discard">Discard session</button>
      <button class="btn secondary big" data-close>Cancel</button>
    </div>`);
  el.querySelector('[data-action="discard"]').addEventListener('click', async () => {
    close();
    const ok = await confirmDialog('Discard this workout? All its sets will be deleted.', { ok: 'Discard', danger: true });
    if (!ok) return;
    store.dispatch('session.discard', { id: session.id });
    toast('Workout discarded');
  });
}

export function buildSummary(session, endedAt) {
  const flags = sessionFlags(session.sets);
  return {
    date: session.date,
    durationSeconds: Math.round((Date.parse(endedAt) - Date.parse(session.startedAt)) / 1000),
    exerciseCount: new Set(session.sets.map((s) => s.exerciseId)).size,
    workSetCount: session.sets.filter((s) => s.type === 'WORK').length,
    prs: [...session.sets].sort(byTime).filter((s) => isPr(flags.get(s.id))).map((s) => ({
      name: s.exerciseName,
      weightKg: s.weightKg,
      reps: s.reps,
      kind: flags.get(s.id).heaviest ? 'heaviest weight' : 'rep record',
    })),
  };
}

async function endSession(session) {
  const empty = session.sets.length === 0;
  const ok = await confirmDialog(empty ? "No sets logged. This workout won't be saved." : 'End workout?', { ok: 'End workout' });
  if (!ok) return;
  const endedAt = new Date().toISOString();
  const summary = buildSummary(session, endedAt); // before dispatching, while the records still predate this workout
  store.dispatch('session.end', { id: session.id, endedAt });
  if (!empty) store.cacheSessionDetail(session.id, { ...session, endedAt });
  if (empty) {
    toast('Empty workout discarded');
    return;
  }
  navigate({ tab: 'workout', summary });
}

function renderSummary(container, summary) {
  container.innerHTML = `
    <section class="screen summary">
      <h1>Workout complete</h1>
      <p class="muted">${formatLongDate(summary.date)}</p>
      <p class="duration" id="summary-duration">${formatDuration(summary.durationSeconds)}</p>
      <div class="stats-row">
        <div data-stat="exercises"><strong>${summary.exerciseCount}</strong><span>exercises</span></div>
        <div data-stat="work-sets"><strong>${summary.workSetCount}</strong><span>work sets</span></div>
      </div>
      ${summary.prs.length ? `
        <h2>Personal records</h2>
        <ul class="pr-list">${summary.prs.map((pr) => `<li>🏆 ${esc(pr.name)} — ${formatSet(pr.weightKg, pr.reps)} · ${pr.kind}</li>`).join('')}</ul>` : ''}
      <button class="btn primary big" data-action="done">Done</button>
    </section>`;
  container.querySelector('[data-action="done"]').addEventListener('click', () => navigate({ tab: 'workout' }));
}
