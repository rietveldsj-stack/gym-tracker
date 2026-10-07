import * as store from '../store.js';
import { navigate } from '../router.js';
import { openSheet } from '../ui.js';
import { barChart } from '../charts.js';
import { localDateIso } from '../util.js';
import { icon } from '../icons.js';
import { monthDays, addMonths, workoutsByDay } from '../calendar.js';
import { renderDetail, resetDetailFetch } from './history.js';
import {
  MUSCLE_GROUPS, muscleLabel, formatDate, formatLongDate, formatMonth, formatTime, formatDuration, formatSetCount,
} from '../format.js';

const WEEKDAYS = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
const monthIndex = ({ year, month }) => year * 12 + month;

let selected = null;
let shownMonth = null;

export function render(container, route) {
  if (route.sessionId) {
    renderDetail(container, route.sessionId, { label: 'Stats', tab: 'stats' });
    return;
  }
  resetDetailFetch();
  container.innerHTML = `
    <section class="screen">
      <header class="topbar"><h1>Stats</h1></header>
      ${calendarHtml()}
      ${weekHtml()}
    </section>`;
  container.querySelectorAll('[data-month]').forEach((button) => button.addEventListener('click', () => {
    shownMonth = addMonths(shownMonth, Number(button.dataset.month));
    render(container, route);
  }));
  container.querySelectorAll('[data-day]').forEach((button) => button.addEventListener('click', () => {
    openDay(button.dataset.day);
  }));
  container.querySelectorAll('[data-week]').forEach((button) => button.addEventListener('click', () => {
    selected += Number(button.dataset.week);
    render(container, route);
  }));
  drawMuscleChart(container);
}

/** The month calendar: from the month of the first workout up to this month, workout days highlighted. */
function calendarHtml() {
  const byDay = workoutsByDay(store.view().history);
  const now = new Date();
  const today = localDateIso(now);
  const current = { year: now.getFullYear(), month: now.getMonth() };
  const firstDate = [...byDay.keys()].sort()[0];
  const first = firstDate ? { year: Number(firstDate.slice(0, 4)), month: Number(firstDate.slice(5, 7)) - 1 } : current;
  if (!shownMonth || monthIndex(shownMonth) > monthIndex(current)) shownMonth = current;
  if (monthIndex(shownMonth) < monthIndex(first)) shownMonth = first;
  const { leading, days } = monthDays(shownMonth.year, shownMonth.month);
  const cells = days.map((iso) => {
    const workouts = byDay.get(iso);
    const isToday = iso === today;
    const className = `cal-day${workouts ? ' workout' : ''}${isToday ? ' today' : ''}`;
    const todayAttr = isToday ? ' aria-current="date"' : '';
    const day = Number(iso.slice(8));
    if (!workouts) return `<span class="${className}"${todayAttr}>${day}</span>`;
    const label = `${formatLongDate(iso)}, ${workouts.length} workout${workouts.length === 1 ? '' : 's'}`;
    return `<button class="${className}" data-day="${iso}" aria-label="${label}"${todayAttr}>${day}</button>`;
  });
  return `
    <div class="card calendar-card">
      <div class="week-nav">
        <button data-month="-1" aria-label="Previous month"${monthIndex(shownMonth) <= monthIndex(first) ? ' disabled' : ''}>‹</button>
        <h2 id="month-title">${formatMonth(shownMonth.year, shownMonth.month)}</h2>
        <button data-month="1" aria-label="Next month"${monthIndex(shownMonth) >= monthIndex(current) ? ' disabled' : ''}>›</button>
      </div>
      <div class="calendar">
        ${WEEKDAYS.map((d) => `<span class="cal-head" aria-hidden="true">${d}</span>`).join('')}
        ${'<span></span>'.repeat(leading)}
        ${cells.join('')}
      </div>
    </div>`;
}

/** Opens the day's workout, or lets her pick when she trained more than once that day. */
function openDay(iso) {
  const workouts = workoutsByDay(store.view().history).get(iso) ?? [];
  if (workouts.length === 1) {
    navigate({ tab: 'stats', sessionId: workouts[0].id });
    return;
  }
  const { el, close } = openSheet(`
    <h2>${formatLongDate(iso)}</h2>
    <ul class="list">${workouts.map((h) => `
      <li><button class="row nav" data-session="${h.id}">
        <span class="row-text">
          <span>${formatTime(h.startedAt)}</span>
          <span class="row-sub">${[formatDuration(h.durationSeconds), formatSetCount(h.setCount), ...h.muscleGroups.map(muscleLabel)].join(' · ')}</span>
        </span>
        ${icon('chevronRight', { size: 18, className: 'icon chevron' })}
      </button></li>`).join('')}</ul>
    <button class="btn secondary big" data-close>Cancel</button>`);
  el.querySelectorAll('[data-session]').forEach((button) => button.addEventListener('click', () => {
    close();
    navigate({ tab: 'stats', sessionId: button.dataset.session });
  }));
}

function weekHtml() {
  const { weekly, weeklyAsOf } = store.view();
  if (!weekly?.length) {
    return '<div class="card"><p class="empty">Muscle-group stats appear once the app has been online.</p></div>';
  }
  if (selected === null || selected >= weekly.length) selected = weekly.length - 1;
  const week = weekly[selected];
  const isThisWeek = selected === weekly.length - 1;
  const muscles = musclesOf(week);
  return `
    ${navigator.onLine || !weeklyAsOf ? '' : `<p class="muted">As of ${formatDate(localDateIso(new Date(weeklyAsOf)))}</p>`}
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
    </div>`;
}

function musclesOf(week) {
  return MUSCLE_GROUPS.filter((g) => (week.workSetsByMuscle[g] ?? 0) > 0);
}

function drawMuscleChart(container) {
  const canvas = container.querySelector('#muscle-chart');
  if (!canvas) return;
  const week = store.view().weekly[selected];
  const muscles = musclesOf(week);
  barChart(canvas, muscles.map(muscleLabel), muscles.map((g) => week.workSetsByMuscle[g]), { unit: 'work sets', horizontal: true });
}
