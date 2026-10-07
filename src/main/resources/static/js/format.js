export const MUSCLE_GROUPS = ['CHEST', 'BACK', 'SHOULDERS', 'BICEPS', 'TRICEPS', 'QUADS', 'HAMSTRINGS', 'CALVES', 'GLUTES', 'CORE'];

export function muscleLabel(group) {
  return group ? group.charAt(0) + group.slice(1).toLowerCase() : '';
}

export function formatWeight(kg) {
  const n = Number(kg);
  const text = Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0$/, '');
  return `${text} kg`;
}

export function formatSet(weightKg, reps) {
  return Number(weightKg) === 0 ? `Bodyweight × ${reps}` : `${formatWeight(weightKg)} × ${reps}`;
}

export function formatSetCount(n) {
  return `${n} ${n === 1 ? 'set' : 'sets'}`;
}

/** H:MM:SS, for the live workout timer. */
export function formatClock(totalSeconds) {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return `${h}:${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

/** M:SS, rounded up, for the rest timer. */
export function formatCountdown(totalSeconds) {
  const s = Math.max(0, Math.ceil(totalSeconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** "42 min" or "1 h 05 min". */
export function formatDuration(totalSeconds) {
  const minutes = Math.round(totalSeconds / 60);
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`;
}

function toDate(iso) {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number);
  return new Date(y, m - 1, d);
}

/** "Mon 5 Oct", or "5 Oct" without the weekday. */
export function formatDate(iso, { weekday = true } = {}) {
  return toDate(iso).toLocaleDateString('en-GB', { ...(weekday && { weekday: 'short' }), day: 'numeric', month: 'short' });
}

/** "Monday 5 October". */
export function formatLongDate(iso) {
  return toDate(iso).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' });
}

/** "October 2026". */
export function formatMonth(year, month) {
  return new Date(year, month, 1).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
}

/** "08:00", the phone's local time of an ISO timestamp. */
export function formatTime(iso) {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
}

/** "Good morning, Janet": morning from 05:00, afternoon from 12:00, evening from 18:00 (and through the night). */
export function greeting(now, name) {
  const hour = now.getHours();
  const part = hour >= 5 && hour < 12 ? 'morning' : hour >= 12 && hour < 18 ? 'afternoon' : 'evening';
  const trimmed = String(name ?? '').trim();
  return trimmed ? `Good ${part}, ${trimmed}` : `Good ${part}`;
}

/** Reads "42.5" or "42,5"; rounds to 0.25 kg and clamps to 0–500. Returns null when it isn't a number. */
export function parseWeight(text) {
  const trimmed = String(text ?? '').trim().replace(',', '.');
  if (trimmed === '') return null;
  const n = Number(trimmed);
  if (!Number.isFinite(n)) return null;
  return Math.min(500, Math.max(0, Math.round(n * 4) / 4));
}
