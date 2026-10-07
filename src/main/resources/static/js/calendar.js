import { localDateIso } from './util.js';

/** The days of a month (month 0–11) as YYYY-MM-DD, and how many blank cells come before day 1 in a Monday-first week. */
export function monthDays(year, month) {
  const count = new Date(year, month + 1, 0).getDate();
  const days = Array.from({ length: count }, (_, i) => localDateIso(new Date(year, month, i + 1)));
  return { leading: (new Date(year, month, 1).getDay() + 6) % 7, days };
}

export function addMonths({ year, month }, delta) {
  const date = new Date(year, month + delta, 1);
  return { year: date.getFullYear(), month: date.getMonth() };
}

/** History summaries grouped by their date, each day's workouts earliest first. */
export function workoutsByDay(history) {
  const byDay = new Map();
  for (const workout of [...history].sort((a, b) => Date.parse(a.startedAt) - Date.parse(b.startedAt))) {
    if (!byDay.has(workout.date)) byDay.set(workout.date, []);
    byDay.get(workout.date).push(workout);
  }
  return byDay;
}
