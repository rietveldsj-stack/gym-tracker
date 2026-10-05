import { request } from './api.js';
import * as store from './store.js';
import * as sync from './sync.js';
import { getRoute, navigate, setRenderer } from './router.js';
import { toast } from './ui.js';
import { renderLogin } from './views/login.js';
import * as exercises from './views/exercises.js';
import * as workout from './views/workout.js';
import * as history from './views/history.js';

const TABS = [
  { id: 'workout', label: 'Workout', icon: '🏋️', view: workout },
  { id: 'exercises', label: 'Exercises', icon: '📋', view: exercises },
  { id: 'history', label: 'History', icon: '🗓️', view: history },
];

const viewEl = document.getElementById('view');
const tabsEl = document.getElementById('tabs');
const syncEl = document.getElementById('syncbar');
let signedIn = false;

function render() {
  if (!signedIn) return;
  const route = getRoute();
  const current = TABS.find((t) => t.id === route.tab) ?? TABS[0];
  tabsEl.hidden = false;
  tabsEl.innerHTML = TABS.map((t) => `
    <button class="tab${t.id === current.id ? ' active' : ''}" data-tab="${t.id}"${t.id === current.id ? ' aria-current="page"' : ''}>
      <span aria-hidden="true">${t.icon}</span>${t.label}
    </button>`).join('');
  const pending = store.pending();
  syncEl.hidden = pending === 0;
  syncEl.textContent = `${pending} change${pending === 1 ? '' : 's'} waiting to sync`;
  current.view.render(viewEl, route);
}

function showLogin() {
  signedIn = false;
  tabsEl.hidden = true;
  syncEl.hidden = true;
  renderLogin(viewEl, { onSuccess: showApp });
}

function showApp() {
  signedIn = true;
  render();
  sync.flush();
}

// A screen update between finger-down and finger-up replaces the button under the finger and the tap is lost,
// so updates that arrive while a finger is down wait until the tap has been handled.
let pointerDown = false;
let renderPending = false;
const afterTap = new MessageChannel(); // a message runs after the click event, and fake test clocks don't delay it
afterTap.port1.onmessage = () => {
  if (renderPending && !pointerDown) {
    renderPending = false;
    render();
  }
};
const release = () => {
  pointerDown = false;
  if (renderPending) afterTap.port2.postMessage(null);
};
document.addEventListener('pointerdown', () => { pointerDown = true; }, true);
document.addEventListener('pointerup', release, true);
document.addEventListener('pointercancel', release, true);

function onStoreChange() {
  if (pointerDown) renderPending = true;
  else render();
}

tabsEl.addEventListener('click', (event) => {
  const button = event.target.closest('[data-tab]');
  if (button) navigate({ tab: button.dataset.tab });
});

async function boot() {
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  setRenderer(render);
  navigate({ tab: TABS[0].id });
  sync.onUnauthorized(showLogin);
  sync.onRejected((message) => toast(message));
  store.subscribe(onStoreChange);
  sync.startSync();
  const me = await request('GET', '/api/me');
  if (me.kind === 'unauthorized') showLogin();
  else showApp(); // signed in, or offline: work from the saved snapshot
}

boot();
