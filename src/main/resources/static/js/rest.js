import { request } from './api.js';
import { getPrefs } from './prefs.js';
import { formatCountdown } from './format.js';

// The rest bar above the tabs. The end time is saved, so the countdown survives closing the app.
const KEY = 'gt.rest.v1';
const OVER_MS = 5000;

let rest = load(); // { endsAt, totalMs } or null
let overUntil = 0;
let sessionOpen = false;
let mode = null; // what the bar shows: hidden | running | over | start
let audio = null;

function load() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || 'null');
  } catch {
    return null;
  }
}

function persist() {
  try {
    localStorage.setItem(KEY, JSON.stringify(rest));
  } catch {
    // ignore: the timer still runs in memory
  }
}

export function initRest() {
  document.addEventListener('pointerdown', unlockAudio, { capture: true });
  document.getElementById('restbar').addEventListener('click', (event) => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.restAdjust) adjustRest(Number(button.dataset.restAdjust));
    else if (button.dataset.action === 'skip-rest') skipRest();
    else if (button.dataset.action === 'start-rest') startRest();
  });
  if (rest && rest.endsAt < Date.now() - OVER_MS) {
    rest = null;
    persist();
  }
  setInterval(tick, 250);
}

/** Called on every render; the rest timer only exists while a session is open. */
export function setSessionOpen(open) {
  sessionOpen = open;
  if (!open && rest) skipRest();
  draw();
}

export function startRest(seconds = getPrefs().restSeconds) {
  rest = { endsAt: Date.now() + seconds * 1000, totalMs: seconds * 1000 };
  overUntil = 0;
  persist();
  schedulePush();
  draw();
}

export function adjustRest(deltaSeconds) {
  if (!rest) return;
  rest = { endsAt: rest.endsAt + deltaSeconds * 1000, totalMs: Math.max(1000, rest.totalMs + deltaSeconds * 1000) };
  persist();
  if (rest.endsAt > Date.now()) schedulePush();
  else cancelPush();
  tick();
}

export function skipRest() {
  rest = null;
  overUntil = 0;
  persist();
  cancelPush();
  draw();
}

export function onSetSaved() {
  if (getPrefs().autoStart) startRest();
}

function tick() {
  if (rest && Date.now() >= rest.endsAt) {
    rest = null;
    persist();
    overUntil = Date.now() + OVER_MS;
    beep();
  }
  if (overUntil && Date.now() >= overUntil) overUntil = 0;
  draw();
}

// Alerts are best effort: a late alert is useless, so these calls are never queued or retried.
function schedulePush() {
  if (getPrefs().alertsEnabled && rest) request('PUT', '/api/rest-timer', { endsAt: new Date(rest.endsAt).toISOString() });
}

function cancelPush() {
  if (getPrefs().alertsEnabled) request('DELETE', '/api/rest-timer');
}

const BAR = {
  hidden: '',
  running: `
    <div class="rest-progress"><span></span></div>
    <div class="rest-row">
      <span class="rest-time" id="rest-time"></span>
      <button class="btn small" data-rest-adjust="-15">−15 s</button>
      <button class="btn small" data-rest-adjust="15">+15 s</button>
      <button class="btn small" data-action="skip-rest">Skip</button>
    </div>`,
  over: '<div class="rest-row"><span class="rest-time">Rest over</span></div>',
  start: '<button class="btn primary big" data-action="start-rest">Start rest</button>',
};

function draw() {
  const bar = document.getElementById('restbar');
  let next = 'hidden';
  if (sessionOpen && rest) next = 'running';
  else if (sessionOpen && overUntil) next = 'over';
  else if (sessionOpen && !getPrefs().autoStart) next = 'start';
  if (next !== mode) {
    // Only rebuild when the mode changes, so a tap on a button is never lost to a re-render.
    mode = next;
    bar.hidden = next === 'hidden';
    bar.innerHTML = BAR[next];
  }
  if (mode === 'running') {
    const left = rest.endsAt - Date.now();
    bar.querySelector('#rest-time').textContent = formatCountdown(left / 1000);
    bar.querySelector('.rest-progress span').style.width = `${Math.max(0, Math.min(100, (left / rest.totalMs) * 100))}%`;
  }
}

function unlockAudio() {
  try {
    const Context = window.AudioContext || window.webkitAudioContext;
    if (!audio && Context) audio = new Context();
    if (audio?.state === 'suspended') audio.resume();
  } catch {
    audio = null;
  }
}

function beep() {
  try {
    if (!audio || audio.state !== 'running') return;
    [0, 0.3].forEach((offset) => {
      const start = audio.currentTime + offset;
      const oscillator = audio.createOscillator();
      const gain = audio.createGain();
      oscillator.frequency.value = 880;
      gain.gain.setValueAtTime(0.3, start);
      gain.gain.exponentialRampToValueAtTime(0.001, start + 0.25);
      oscillator.connect(gain).connect(audio.destination);
      oscillator.start(start);
      oscillator.stop(start + 0.25);
    });
  } catch {
    // no sound available
  }
}
