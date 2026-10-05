import * as store from '../store.js';
import { request } from '../api.js';
import { getRoute, navigate } from '../router.js';
import { esc } from '../util.js';
import { muscleLabel, formatDate, formatLongDate, formatDuration, formatSet, formatSetCount } from '../format.js';
import { groupSets } from './workout.js';

let fetchedFor = null;
const failures = {};

export function render(container, route) {
  if (route.sessionId) {
    renderDetail(container, route.sessionId);
    return;
  }
  fetchedFor = null;
  const { history } = store.view();
  container.innerHTML = `
    <section class="screen">
      <header class="topbar"><h1>History</h1></header>
      ${history.length ? `<ul class="list">${history.map((h) => `
        <li><button class="row" data-session="${h.id}">
          <span>${formatDate(h.date)}</span>
          <span class="row-sub">${[formatDuration(h.durationSeconds), formatSetCount(h.setCount), ...h.muscleGroups.map(muscleLabel)].join(' · ')}</span>
        </button></li>`).join('')}</ul>` : '<p class="empty">No workouts yet.</p>'}
    </section>`;
  container.querySelectorAll('[data-session]').forEach((button) => button.addEventListener('click', () => {
    navigate({ tab: 'history', sessionId: button.dataset.session });
  }));
}

function setsHtml(sets) {
  return groupSets(sets).map((group) => `
    <div class="card">
      <h2>${esc(group.name)}</h2>
      <ul class="sets">
        ${group.sets.map((set, i) => `
          <li class="set-row">
            <span class="set-no">${i + 1}</span>
            <span>${formatSet(set.weightKg, set.reps)}</span>
            ${set.type === 'WARMUP' ? '<span class="tag">Warmup</span>' : ''}
          </li>`).join('')}
      </ul>
    </div>`).join('');
}

function renderDetail(container, id) {
  const summary = store.view().history.find((h) => h.id === id);
  const detail = store.view().sessionDetails[id];
  const body = detail ? setsHtml(detail.sets) : `<p class="empty">${failures[id] ?? 'Loading…'}</p>`;
  container.innerHTML = `
    <section class="screen">
      <header class="topbar"><button class="btn ghost back" data-action="back">‹ History</button></header>
      <h1>${summary ? formatLongDate(summary.date) : 'Workout'}</h1>
      ${summary ? `<p class="muted">${formatDuration(summary.durationSeconds)} · ${formatSetCount(summary.setCount)}</p>` : ''}
      <div id="session-detail">${body}</div>
    </section>`;
  container.querySelector('[data-action="back"]').addEventListener('click', () => navigate({ tab: 'history' }));
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
