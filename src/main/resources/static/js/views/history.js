import * as store from '../store.js';
import { request } from '../api.js';
import { getRoute, navigate } from '../router.js';
import { confirmDialog, toast } from '../ui.js';
import { esc } from '../util.js';
import { icon } from '../icons.js';
import { muscleLabel, formatDate, formatLongDate, formatDuration, formatSet, formatSetCount } from '../format.js';
import { groupSets, exerciseFor, openPicker, openSetSheet } from './workout.js';

let fetchedFor = null;
let editing = null; // the workout whose sets can be changed right now
const failures = {};

/** Leaving a workout's page: opening it again tries loading it again, and starts out read-only. */
export function resetDetail() {
  fetchedFor = null;
  editing = null;
}

export function render(container, route) {
  if (route.sessionId) {
    renderDetail(container, route.sessionId);
    return;
  }
  resetDetail();
  const { history } = store.view();
  container.innerHTML = `
    <section class="screen">
      <header class="topbar"><h1>History</h1></header>
      ${history.length ? `<ul class="list">${history.map((h) => `
        <li><button class="row nav" data-session="${h.id}">
          <span class="row-text">
            <span>${formatDate(h.date)}</span>
            <span class="row-sub">${[formatDuration(h.durationSeconds), formatSetCount(h.setCount), ...h.muscleGroups.map(muscleLabel)].join(' · ')}</span>
          </span>
          ${icon('chevronRight', { size: 18, className: 'icon chevron' })}
        </button></li>`).join('')}</ul>` : '<p class="empty">No workouts yet.</p>'}
    </section>`;
  container.querySelectorAll('[data-session]').forEach((button) => button.addEventListener('click', () => {
    navigate({ tab: 'history', sessionId: button.dataset.session });
  }));
}

function setsHtml(sets, editable) {
  const rowHtml = (set, i) => `
    <span class="set-no">${i + 1}</span>
    <span>${formatSet(set.weightKg, set.reps)}</span>
    ${set.type === 'WARMUP' ? '<span class="tag">Warmup</span>' : ''}`;
  return groupSets(sets).map((group) => `
    <div class="card">
      <h2>${esc(group.name)}</h2>
      <ul class="sets">
        ${group.sets.map((set, i) => (editable
          ? `<li><button class="set-row" data-set="${set.id}">${rowHtml(set, i)}</button></li>`
          : `<li class="set-row">${rowHtml(set, i)}</li>`)).join('')}
      </ul>
      ${editable ? `<button class="btn secondary small" data-add-set="${group.exerciseId}">+ Set</button>` : ''}
    </div>`).join('');
}

/** Deletes the workout after she confirms, and goes back to where she opened it from. */
async function deleteWorkout(id, back, message = 'Delete this workout? All its sets will be deleted.') {
  if (!await confirmDialog(message, { ok: 'Delete workout', danger: true })) return;
  navigate({ tab: back.tab }); // first, so this page doesn't try to reload the workout once it's gone
  store.dispatch('session.discard', { id });
  toast('Workout deleted');
}

/** One workout's sets. `back` is where the back button goes: History by default, or the tab that opened it. */
export function renderDetail(container, id, back = { label: 'History', tab: 'history' }) {
  const summary = store.view().history.find((h) => h.id === id);
  const detail = store.view().sessionDetails[id];
  const editable = Boolean(detail) && editing === id;
  const body = detail ? setsHtml(detail.sets, editable) : `<p class="empty">${failures[id] ?? 'Loading…'}</p>`;
  container.innerHTML = `
    <section class="screen">
      <header class="topbar">
        <button class="btn ghost back" data-action="back">‹ ${back.label}</button>
        ${detail ? `<button class="btn ghost end" data-action="edit">${editable ? 'Done' : 'Edit'}</button>` : ''}
      </header>
      <h1>${summary ? formatLongDate(summary.date) : 'Workout'}</h1>
      ${summary ? `<p class="muted">${formatDuration(summary.durationSeconds)} · ${formatSetCount(summary.setCount)}</p>` : ''}
      <div id="session-detail">${body}</div>
      ${editable ? `
        <div class="stack">
          <button class="btn primary big" data-action="add-exercise">+ Add exercise</button>
          <button class="btn danger big" data-action="delete">Delete workout</button>
        </div>` : ''}
    </section>`;
  container.querySelector('[data-action="back"]').addEventListener('click', () => navigate({ tab: back.tab }));
  container.querySelector('[data-action="edit"]')?.addEventListener('click', () => {
    editing = editable ? null : id;
    renderDetail(container, id, back);
  });
  if (editable) bindEditing(container, id, detail, back);
  if (detail || fetchedFor === id) return;
  fetchedFor = id;
  delete failures[id];
  request('GET', `/api/sessions/${id}`).then((result) => {
    if (result.kind === 'ok') {
      store.cacheSessionDetail(id, result.data);
      return;
    }
    failures[id] = result.kind === 'network' ? 'Not available offline.' : 'Could not load this workout.';
    const el = document.getElementById('session-detail');
    if (el && getRoute().sessionId === id) el.innerHTML = `<p class="empty">${failures[id]}</p>`;
  });
}

function bindEditing(container, id, detail, back) {
  const options = { past: detail.sets, onDeleteWorkout: (message) => deleteWorkout(id, back, message) };
  container.querySelectorAll('[data-set]').forEach((button) => button.addEventListener('click', () => {
    const set = detail.sets.find((s) => s.id === button.dataset.set);
    openSetSheet(id, exerciseFor(set.exerciseId, detail.sets), set, options);
  }));
  container.querySelectorAll('[data-add-set]').forEach((button) => button.addEventListener('click', () => {
    openSetSheet(id, exerciseFor(button.dataset.addSet, detail.sets), null, options);
  }));
  container.querySelector('[data-action="add-exercise"]').addEventListener('click', () => {
    openPicker((exercise) => openSetSheet(id, exercise, null, options));
  });
  container.querySelector('[data-action="delete"]').addEventListener('click', () => deleteWorkout(id, back));
}
