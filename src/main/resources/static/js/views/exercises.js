import * as store from '../store.js';
import { openSheet, confirmDialog, toast } from '../ui.js';
import { esc, uuid } from '../util.js';
import { MUSCLE_GROUPS, muscleLabel } from '../format.js';

export function render(container) {
  const { exercises } = store.view();
  const groups = MUSCLE_GROUPS
    .map((group) => ({ group, items: exercises.filter((e) => e.muscleGroup === group) }))
    .filter((g) => g.items.length);
  container.innerHTML = `
    <section class="screen">
      <header class="topbar">
        <h1>Exercises</h1>
        <button class="btn icon primary" data-action="add" aria-label="Add exercise">+</button>
      </header>
      ${groups.length ? groups.map(({ group, items }) => `
        <h2 class="group-title">${muscleLabel(group)}</h2>
        <ul class="list">
          ${items.map((e) => `<li><button class="row" data-id="${e.id}">${esc(e.name)}</button></li>`).join('')}
        </ul>`).join('') : '<p class="empty">No exercises yet. Tap + to add your first one.</p>'}
    </section>`;
  container.querySelector('[data-action="add"]').addEventListener('click', () => openExerciseForm());
  container.querySelectorAll('[data-id]').forEach((button) => button.addEventListener('click', () => {
    openExerciseForm(exercises.find((e) => e.id === button.dataset.id));
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
