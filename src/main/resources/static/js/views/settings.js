import { openSheet } from '../ui.js';
import { getPrefs, updatePrefs } from '../prefs.js';
import { formatCountdown } from '../format.js';
import { enableAlerts } from '../push.js';

export function openSettings() {
  const prefs = getPrefs();
  const { el } = openSheet(`
    <h2>Settings</h2>
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
    <div class="setting">
      <span>Lock-screen alerts</span>
      <span id="alerts-state">${prefs.alertsEnabled ? 'On' : '<button class="btn secondary small" data-action="enable-alerts">Enable</button>'}</span>
    </div>
    <p class="muted" id="alerts-message" hidden></p>
    <button class="btn secondary big" data-close>Done</button>`);
  el.querySelectorAll('[data-rest]').forEach((button) => button.addEventListener('click', () => {
    const seconds = Math.min(600, Math.max(15, getPrefs().restSeconds + Number(button.dataset.rest)));
    updatePrefs({ restSeconds: seconds });
    el.querySelector('#rest-seconds').textContent = formatCountdown(seconds);
  }));
  el.querySelector('#auto-start').addEventListener('change', (event) => updatePrefs({ autoStart: event.target.checked }));
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
}
