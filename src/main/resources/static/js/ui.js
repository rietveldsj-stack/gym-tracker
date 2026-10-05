import { esc } from './util.js';

let toastTimer;

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
