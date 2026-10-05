import * as store from '../store.js';
import { barChart } from '../charts.js';
import { localDateIso } from '../util.js';
import { MUSCLE_GROUPS, muscleLabel, formatDate } from '../format.js';

let selected = null;

export function render(container) {
  const { weekly, weeklyAsOf } = store.view();
  if (!weekly?.length) {
    container.innerHTML = `
      <section class="screen">
        <header class="topbar"><h1>Stats</h1></header>
        <p class="empty">Stats appear once the app has been online.</p>
      </section>`;
    return;
  }
  if (selected === null || selected >= weekly.length) selected = weekly.length - 1;
  const week = weekly[selected];
  const isThisWeek = selected === weekly.length - 1;
  const muscles = MUSCLE_GROUPS.filter((g) => (week.workSetsByMuscle[g] ?? 0) > 0);
  container.innerHTML = `
    <section class="screen">
      <header class="topbar"><h1>Stats</h1></header>
      ${navigator.onLine || !weeklyAsOf ? '' : `<p class="muted">As of ${formatDate(localDateIso(new Date(weeklyAsOf)))}</p>`}
      <div class="card">
        <h2>Workouts per week</h2>
        <div class="chart-wrap"><canvas id="weekly-chart" role="img" aria-label="Workouts per week, last 12 weeks"></canvas></div>
      </div>
      <div class="card">
        <div class="week-nav">
          <button data-week="-1" aria-label="Previous week"${selected === 0 ? ' disabled' : ''}>‹</button>
          <h2 id="week-title">${isThisWeek ? 'This week' : `Week of ${formatDate(week.weekStart, { weekday: false })}`}</h2>
          <button data-week="1" aria-label="Next week"${isThisWeek ? ' disabled' : ''}>›</button>
        </div>
        <p class="muted">Work sets per muscle group</p>
        ${muscles.length
          ? `<div class="chart-wrap" style="height: ${muscles.length * 36 + 40}px"><canvas id="muscle-chart" role="img" aria-label="Work sets per muscle group"></canvas></div>`
          : '<p class="empty">No workouts this week</p>'}
      </div>
    </section>`;
  barChart(
    container.querySelector('#weekly-chart'),
    weekly.map((w) => formatDate(w.weekStart, { weekday: false })),
    weekly.map((w) => w.workouts),
    { unit: 'workouts' },
  );
  if (muscles.length) {
    barChart(
      container.querySelector('#muscle-chart'),
      muscles.map(muscleLabel),
      muscles.map((g) => week.workSetsByMuscle[g]),
      { unit: 'work sets', horizontal: true },
    );
  }
  container.querySelectorAll('[data-week]').forEach((button) => button.addEventListener('click', () => {
    selected += Number(button.dataset.week);
    render(container);
  }));
}
