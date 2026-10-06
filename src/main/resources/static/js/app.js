import { request } from './api.js';
import * as store from './store.js';
import * as sync from './sync.js';
import { getRoute, navigate, setRenderer } from './router.js';
import { toast } from './ui.js';
import { initRest, setSessionOpen } from './rest.js';
import { renderAuth } from './views/auth.js';
import { accountEmail, useAccount } from './account.js';
import { onSignedOut } from './logout.js';
import { applyTheme } from './theme.js';
import * as exercises from './views/exercises.js';
import * as workout from './views/workout.js';
import * as history from './views/history.js';
import * as stats from './views/stats.js';

const TABS = [
  { id: 'workout', label: 'Workout', icon: '🏋️', view: workout },
  { id: 'exercises', label: 'Exercises', icon: '📋', view: exercises },
  { id: 'history', label: 'History', icon: '🗓️', view: history },
  { id: 'stats', label: 'Stats', icon: '📈', view: stats },
];

const viewEl = document.getElementById('view');
const tabsEl = document.getElementById('tabs');
const syncEl = document.getElementById('syncbar');
let signedIn = false;
let loginShown = false;
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
  setSessionOpen(Boolean(store.view().activeSession));
  current.view.render(viewEl, route);
}

function showLogin(options = {}) {
  if (loginShown) return; // a background sync attempt must not wipe what the user is typing
  loginShown = true;
  signedIn = false;
  tabsEl.hidden = true;
  syncEl.hidden = true;
  setSessionOpen(false);
  renderAuth(viewEl, { ...options, onSuccess: showApp });
}

/** Opens the app. `email` is given right after signing in; on a normal start the saved account is used. */
function showApp(email) {
  loginShown = false;
  signedIn = true;
  if (email) useAccount(email);
  render();
  sync.flush();
}

/** A reset link opens the app at #/reset?token=…; the token is taken out of the address bar and history. */
function takeResetToken() {
  const match = window.location.hash.match(/^#\/reset\?token=([A-Za-z0-9_-]+)/);
  if (!match) return null;
  window.history.replaceState(null, '', window.location.pathname);
  return match[1];
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
  applyTheme();
  setRenderer(render);
  initRest();
  navigate({ tab: TABS[0].id });
  sync.onUnauthorized(() => showLogin());
  onSignedOut(() => showLogin());
  sync.onRejected((message) => toast(message));
  store.subscribe(onStoreChange);
  window.addEventListener('gt:theme', onStoreChange);
  sync.startSync();
  const resetToken = takeResetToken();
  if (resetToken) {
    showLogin({ mode: 'reset', token: resetToken });
    return;
  }
  if (accountEmail()) {
    showApp(); // open straight from the saved data; the sync that follows shows the login screen on a 401
    return;
  }
  const me = await request('GET', '/api/me');
  if (me.kind === 'ok') showApp(me.data.email);
  else showLogin(); // signed out, or offline with no account on this phone yet
}

boot();
