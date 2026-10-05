import { esc } from './util.js';

let toastTimer;

// The second tap of a double-tap on a sheet's button would otherwise land on whatever lies under the sheet once it
// closed (often a tab). A click within GHOST_MS of the tap that closed a sheet, at almost the same spot and outside
// any open sheet, is that second tap and is ignored. Event timestamps come from the browser, so this also holds
// under the tests' fake clock.
const GHOST_MS = 350;
const GHOST_PX = 30;
let lastClick = { stamp: -Infinity, x: 0, y: 0 };
let ghost = { until: -Infinity, x: 0, y: 0 };
document.addEventListener('click', (event) => {
  const near = Math.abs(event.clientX - ghost.x) <= GHOST_PX && Math.abs(event.clientY - ghost.y) <= GHOST_PX;
  const inSheet = event.target.closest?.('#overlay-root');
  if (event.timeStamp < ghost.until && near && !inSheet) {
    event.stopPropagation();
    event.preventDefault();
    return;
  }
  lastClick = { stamp: event.timeStamp, x: event.clientX, y: event.clientY };
}, true);

export function toast(message, ms = 2500) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), ms);
}

/** Opens a bottom sheet. Tapping the backdrop or any [data-close] element closes it. */
export function openSheet(html, { onClose } = {}) {
  const backdrop = document.createElement('div');
  backdrop.className = 'backdrop';
  backdrop.innerHTML = `<div class="sheet" role="dialog" aria-modal="true">${html}</div>`;
  document.getElementById('overlay-root').appendChild(backdrop);
  const el = backdrop.firstElementChild;
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    ghost = { until: lastClick.stamp + GHOST_MS, x: lastClick.x, y: lastClick.y };
    backdrop.remove();
    onClose?.();
  };
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop || event.target.closest('[data-close]')) close();
  });
  return { el, close };
}

export function confirmDialog(message, { ok = 'OK', danger = false } = {}) {
  return new Promise((resolve) => {
    let answer = false;
    const { el, close } = openSheet(`
      <p class="confirm-text">${esc(message)}</p>
      <div class="actions">
        <button class="btn secondary" data-close>Cancel</button>
        <button class="btn ${danger ? 'danger' : 'primary'}" data-confirm>${esc(ok)}</button>
      </div>`, { onClose: () => resolve(answer) });
    el.querySelector('[data-confirm]').addEventListener('click', () => {
      answer = true;
      close();
    });
  });
}
