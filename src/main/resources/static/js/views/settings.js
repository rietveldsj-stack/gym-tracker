import { openSheet } from '../ui.js';
import { getPrefs, updatePrefs } from '../prefs.js';
import { formatCountdown } from '../format.js';
import { enableAlerts } from '../push.js';
import { accountEmail } from '../account.js';
import { logout } from '../logout.js';
import * as store from '../store.js';
import * as sync from '../sync.js';
import { esc } from '../util.js';
import { THEMES, setTheme } from '../theme.js';

export function openSettings() {
  const prefs = getPrefs();
  const { el, close } = openSheet(`
    <h2>Settings</h2>
    <form class="stack" id="name-form" novalidate>
      <label class="field">Name
        <input name="name" autocomplete="given-name" maxlength="40" value="${esc(store.view().name ?? '')}">
      </label>
      <p class="error" id="name-error" hidden></p>
    </form>
    <div class="setting">
      <span>Rest time</span>
      <div class="stepper compact">
        <button class="btn step" data-rest="-15" aria-label="Shorter rest">−</button>
        <output id="rest-seconds">${formatCountdown(prefs.restSeconds)}</output>
        <button class="btn step" data-rest="15" aria-label="Longer rest">+</button>
      </div>
    </div>
    <label class="setting">
      <span>Auto-start rest after each set</span>
      <input type="checkbox" role="switch" id="auto-start"${prefs.autoStart ? ' checked' : ''}>
    </label>
    <div class="stack">
      <span>Theme</span>
      <div class="segmented three" role="radiogroup" aria-label="Theme">
        ${THEMES.map((t) => `<button type="button" role="radio" data-theme-choice="${t.id}" aria-checked="${prefs.theme === t.id}">${esc(t.label)}</button>`).join('')}
      </div>
    </div>
    <div class="setting">
      <span>Lock-screen alerts</span>
      <span id="alerts-state">${prefs.alertsEnabled ? 'On' : '<button class="btn secondary small" data-action="enable-alerts">Enable</button>'}</span>
    </div>
    <p class="muted" id="alerts-message" hidden></p>
    <div class="account">
      <p class="muted">Signed in as ${esc(accountEmail() ?? '')}</p>
      <button class="btn danger" data-action="logout">Log out</button>
    </div>
    <button class="btn secondary big" data-close>Done</button>`);
  const nameForm = el.querySelector('#name-form');
  const saveName = () => {
    const name = nameForm.elements.name.value.trim();
    const error = el.querySelector('#name-error');
    error.hidden = Boolean(name);
    if (!name) {
      error.textContent = 'Enter your name';
      return;
    }
    if (name !== store.view().name) store.dispatch('name.put', { name });
  };
  nameForm.addEventListener('submit', (event) => {
    event.preventDefault();
    saveName();
    nameForm.elements.name.blur(); // closes the keyboard
  });
  nameForm.elements.name.addEventListener('change', saveName);
  el.querySelectorAll('[data-rest]').forEach((button) => button.addEventListener('click', () => {
    const seconds = Math.min(600, Math.max(15, getPrefs().restSeconds + Number(button.dataset.rest)));
    updatePrefs({ restSeconds: seconds });
    el.querySelector('#rest-seconds').textContent = formatCountdown(seconds);
  }));
  el.querySelector('#auto-start').addEventListener('change', (event) => updatePrefs({ autoStart: event.target.checked }));
  el.querySelectorAll('[data-theme-choice]').forEach((button) => button.addEventListener('click', () => {
    setTheme(button.dataset.themeChoice);
    el.querySelectorAll('[data-theme-choice]').forEach((b) => b.setAttribute('aria-checked', String(b === button)));
  }));
  el.querySelector('[data-action="enable-alerts"]')?.addEventListener('click', async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    const result = await enableAlerts();
    if (result.ok) {
      el.querySelector('#alerts-state').textContent = 'On';
      return;
    }
    button.disabled = false;
    const message = el.querySelector('#alerts-message');
    message.textContent = result.message;
    message.hidden = false;
  });
  el.querySelector('[data-action="logout"]').addEventListener('click', () => {
    close();
    confirmLogout();
  });
}

function confirmLogout() {
  const waiting = store.pending();
  const warning = waiting
    ? `${waiting} change${waiting === 1 ? " hasn't" : "s haven't"} synced yet and will be lost.`
    : "You'll need your email and password to sign in again.";
  const { el, close } = openSheet(`
    <h2>Log out?</h2>
    <p class="confirm-text">${esc(warning)}</p>
    <p class="error" id="logout-error" hidden></p>
    <div class="stack">
      ${waiting ? '<button class="btn secondary big" data-action="retry">Try again</button>' : ''}
      <button class="btn danger big" data-action="confirm">${waiting ? 'Log out anyway' : 'Log out'}</button>
      <button class="btn secondary big" data-close>Cancel</button>
    </div>`);
  el.querySelector('[data-action="retry"]')?.addEventListener('click', async () => {
    await sync.flush();
    close();
    confirmLogout();
  });
  el.querySelector('[data-action="confirm"]').addEventListener('click', async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    const result = await logout();
    if (result.ok) {
      close();
      return;
    }
    button.disabled = false;
    const error = el.querySelector('#logout-error');
    error.textContent = result.message;
    error.hidden = false;
  });
}
