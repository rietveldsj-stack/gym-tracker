import { request } from './api.js';
import * as store from './store.js';
import * as sync from './sync.js';
import { getRoute, navigate, setRenderer } from './router.js';
import { toast } from './ui.js';
import { renderLogin } from './views/login.js';
import * as exercises from './views/exercises.js';

const TABS = [
  { id: 'exercises', label: 'Exercises', icon: '📋', view: exercises },
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
  store.subscribe(render);
  sync.startSync();
  const me = await request('GET', '/api/me');
  if (me.kind === 'unauthorized') showLogin();
  else showApp(); // signed in, or offline: work from the saved snapshot
}

boot();
