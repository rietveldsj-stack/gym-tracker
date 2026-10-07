import * as store from '../store.js';
import { request } from '../api.js';
import { navigate } from '../router.js';
import { openSheet, confirmDialog, toast } from '../ui.js';
import { esc, uuid } from '../util.js';
import { MUSCLE_GROUPS, muscleLabel, formatSet, formatWeight, formatDate } from '../format.js';
import { lineChart } from '../charts.js';
import { icon } from '../icons.js';

let fetchedFor = null;
let chartMode = 'weight';

export function render(container, route = {}) {
  if (route.exerciseId) {
    renderDetail(container, route.exerciseId);
    return;
  }
  fetchedFor = null;
  const { exercises } = store.view();
  const groups = MUSCLE_GROUPS
    .map((group) => ({ group, items: exercises.filter((e) => e.muscleGroup === group) }))
    .filter((g) => g.items.length);
  container.innerHTML = `
    <section class="screen">
      <header class="topbar">
        <h1>Exercises</h1>
        <button class="btn icon primary" data-action="add" aria-label="Add exercise">${icon('plus')}</button>
      </header>
      ${groups.length ? groups.map(({ group, items }) => `
        <h2 class="group-title">${muscleLabel(group)}</h2>
        <ul class="list">
          ${items.map((e) => `<li><button class="row nav" data-id="${e.id}"><span class="row-text">${esc(e.name)}</span>${icon('chevronRight', { size: 18, className: 'icon chevron' })}</button></li>`).join('')}
        </ul>`).join('') : '<p class="empty">No exercises yet. Tap + to add your first one.</p>'}
    </section>`;
  container.querySelector('[data-action="add"]').addEventListener('click', () => openExerciseForm());
  container.querySelectorAll('[data-id]').forEach((button) => button.addEventListener('click', () => {
    navigate({ tab: 'exercises', exerciseId: button.dataset.id });
  }));
}

export function validateExercise(name, group, selfId, exercises) {
  if (!name) return 'Enter a name';
  if (name.length > 60) return 'Name can be at most 60 characters';
  if (!group) return 'Choose a muscle group';
  const clash = exercises.find((e) => e.id !== selfId && e.name.toLowerCase() === name.toLowerCase());
  return clash ? `You already have an exercise called '${clash.name}'` : null;
}

/** Create (exercise = null) or edit form in a sheet. */
export function openExerciseForm(exercise = null, { onSaved, onDeleted } = {}) {
  let group = exercise?.muscleGroup ?? null;
  const { el, close } = openSheet(`
    <h2>${exercise ? 'Edit exercise' : 'New exercise'}</h2>
    <form class="stack" novalidate>
      <label class="field">Name
        <input name="exercise-name" maxlength="60" autocomplete="off" value="${esc(exercise?.name ?? '')}">
      </label>
      <fieldset class="chips" aria-label="Muscle group">
        ${MUSCLE_GROUPS.map((g) => `
          <button type="button" class="chip${g === group ? ' selected' : ''}" data-group="${g}" aria-pressed="${g === group}">${muscleLabel(g)}</button>`).join('')}
      </fieldset>
      <p class="error" hidden></p>
      <button class="btn primary big" type="submit">Save</button>
      ${exercise ? '<button class="btn danger" type="button" data-action="delete">Delete exercise</button>' : ''}
    </form>`);
  const form = el.querySelector('form');
  const error = el.querySelector('.error');
  el.querySelectorAll('[data-group]').forEach((chip) => chip.addEventListener('click', () => {
    group = chip.dataset.group;
    el.querySelectorAll('[data-group]').forEach((c) => {
      c.classList.toggle('selected', c === chip);
      c.setAttribute('aria-pressed', String(c === chip));
    });
  }));
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const name = form.elements.namedItem('exercise-name').value.trim();
    const problem = validateExercise(name, group, exercise?.id, store.view().exercises);
    if (problem) {
      error.textContent = problem;
      error.hidden = false;
      return;
    }
    const id = exercise?.id ?? uuid();
    store.dispatch('exercise.put', { id, name, muscleGroup: group });
    close();
    onSaved?.(store.view().exercises.find((e) => e.id === id));
  });
  el.querySelector('[data-action="delete"]')?.addEventListener('click', async () => {
    close();
    const ok = await confirmDialog(`Delete "${exercise.name}"? Past workouts keep their sets.`, { ok: 'Delete', danger: true });
    if (!ok) return;
    store.dispatch('exercise.delete', { id: exercise.id });
    toast('Exercise deleted');
    onDeleted?.();
  });
}

function emptyChartMessage(stats, records) {
  if (!stats) return navigator.onLine ? 'Loading…' : 'Not available offline.';
  return records.repRecords.length ? 'Only bodyweight sets so far' : 'No work sets yet';
}

function renderDetail(container, id) {
  const exercise = store.view().exercises.find((e) => e.id === id);
  if (!exercise) {
    navigate({ tab: 'exercises' });
    return;
  }
  const stats = store.view().exerciseStats[id];
  const records = stats?.records ?? exercise.records ?? { heaviest: null, repRecords: [] };
  const points = stats?.sessions ?? [];
  const { heaviest } = records;
  container.innerHTML = `
    <section class="screen">
      <header class="topbar">
        <button class="btn ghost back" data-action="back">‹ Exercises</button>
        <button class="btn secondary small" data-action="edit">Edit</button>
      </header>
      <h1>${esc(exercise.name)}</h1>
      <p class="muted">${muscleLabel(exercise.muscleGroup)}</p>
      <div class="card">
        <h2>Heaviest weight</h2>
        <p class="big-number" id="heaviest">${heaviest ? formatSet(heaviest.weightKg, heaviest.reps) : '—'}</p>
        ${heaviest ? `<p class="muted">${formatDate(heaviest.date, { weekday: false })}</p>` : ''}
      </div>
      <div class="card">
        <h2>Progress</h2>
        ${points.length ? `
          <div class="segmented" role="radiogroup" aria-label="Chart">
            <button type="button" role="radio" data-mode="weight" aria-checked="${chartMode === 'weight'}">Heaviest weight</button>
            <button type="button" role="radio" data-mode="e1rm" aria-checked="${chartMode === 'e1rm'}">Estimated 1RM</button>
          </div>
          <div class="chart-wrap"><canvas id="progress-chart" role="img" aria-label="Progress per workout"></canvas></div>`
          : `<p class="empty">${emptyChartMessage(stats, records)}</p>`}
      </div>
      <div class="card">
        <h2>Rep records</h2>
        ${records.repRecords.length ? `
          <table class="records">
            <thead><tr><th>Weight</th><th>Most reps</th><th>Date</th></tr></thead>
            <tbody>${records.repRecords.map((r) => `
              <tr>
                <td>${Number(r.weightKg) === 0 ? 'Bodyweight' : formatWeight(r.weightKg)}</td>
                <td>${r.reps}</td>
                <td>${formatDate(r.date, { weekday: false })}</td>
              </tr>`).join('')}
            </tbody>
          </table>` : '<p class="empty">No work sets yet</p>'}
      </div>
    </section>`;
  container.querySelector('[data-action="back"]').addEventListener('click', () => navigate({ tab: 'exercises' }));
  container.querySelector('[data-action="edit"]').addEventListener('click', () => {
    openExerciseForm(exercise, { onDeleted: () => navigate({ tab: 'exercises' }) });
  });
  container.querySelectorAll('[data-mode]').forEach((button) => button.addEventListener('click', () => {
    chartMode = button.dataset.mode;
    renderDetail(container, id);
  }));
  if (points.length) {
    lineChart(
      container.querySelector('#progress-chart'),
      points.map((p) => formatDate(p.date, { weekday: false })),
      points.map((p) => Number(chartMode === 'weight' ? p.maxWeightKg : p.est1rmKg)),
      'kg',
    );
  }
  if (fetchedFor !== id) {
    fetchedFor = id;
    request('GET', `/api/exercises/${id}/stats`).then((result) => {
      if (result.kind === 'ok') store.cacheExerciseStats(id, result.data);
    });
  }
}
