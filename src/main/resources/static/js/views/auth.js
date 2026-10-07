import { login, request } from '../api.js';
import { esc } from '../util.js';

const NO_CONNECTION = 'No connection. Try again when you have signal.';

function messageFor(result, fallback) {
  return result.kind === 'network' ? NO_CONNECTION : result.message || fallback;
}

function passwordField(label, autocomplete) {
  return `
    <label class="field">${label}
      <input name="password" type="password" autocomplete="${autocomplete}" required>
    </label>
    <button type="button" class="link" data-show-password aria-pressed="false">Show password</button>`;
}

const emailField = (email = '') => `
  <label class="field">Email
    <input name="email" type="email" autocomplete="username" autocapitalize="none" autocorrect="off" spellcheck="false" value="${esc(email)}" required>
  </label>`;

const SCREENS = {
  login: ({ notice, email }) => `
    <img class="app-icon" src="/icons/icon-192.png" alt="" width="72" height="72">
    <h1>Gym Tracker</h1>
    ${notice ? `<p class="notice">${esc(notice)}</p>` : ''}
    <form class="stack" novalidate>
      ${emailField(email)}
      ${passwordField('Password', 'current-password')}
      <p class="error" hidden></p>
      <button class="btn primary big" type="submit">Sign in</button>
    </form>
    <div class="auth-links">
      <button type="button" class="link" data-go="register">Create account</button>
      <button type="button" class="link" data-go="forgot">Forgot password?</button>
    </div>`,
  register: () => `
    <h1>Create account</h1>
    <form class="stack" novalidate>
      <label class="field">Your name
        <input name="name" autocomplete="given-name" maxlength="40" required>
      </label>
      ${emailField()}
      ${passwordField('Password', 'new-password')}
      <label class="field">Invite code
        <input name="inviteCode" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" required>
      </label>
      <p class="error" hidden></p>
      <button class="btn primary big" type="submit">Create account</button>
    </form>
    <div class="auth-links"><button type="button" class="link" data-go="login">I already have an account</button></div>`,
  forgot: () => `
    <h1>Forgot password</h1>
    <form class="stack" novalidate>
      ${emailField()}
      <p class="error" hidden></p>
      <button class="btn primary big" type="submit">Send reset link</button>
    </form>
    <div class="auth-links"><button type="button" class="link" data-go="login">Back to sign in</button></div>`,
  reset: () => `
    <h1>New password</h1>
    <form class="stack" novalidate>
      ${passwordField('New password', 'new-password')}
      <p class="error" hidden></p>
      <button class="btn primary big" type="submit">Change password</button>
    </form>
    <div class="auth-links"><button type="button" class="link" data-go="login">Back to sign in</button></div>`,
};

/** Sign-in and account screens. onSuccess(email) opens the app for that account. */
export function renderAuth(container, { mode = 'login', token = null, notice = '', email = '', onSuccess }) {
  const go = (next) => renderAuth(container, { token, onSuccess, ...next });
  container.innerHTML = `<section class="screen login">${SCREENS[mode]({ notice, email })}</section>`;
  container.querySelectorAll('[data-go]').forEach((link) => link.addEventListener('click', () => go({ mode: link.dataset.go })));
  container.querySelectorAll('[data-show-password]').forEach((toggle) => toggle.addEventListener('click', () => {
    const input = container.querySelector('input[name="password"]');
    const show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    toggle.setAttribute('aria-pressed', String(show));
    toggle.textContent = show ? 'Hide password' : 'Show password';
  }));

  const form = container.querySelector('form');
  const error = container.querySelector('.error');
  const field = (name) => String(new FormData(form).get(name) ?? '');
  const submits = {
    async login() {
      await request('GET', '/api/me'); // makes sure the CSRF cookie exists
      const result = await login(field('email').trim(), field('password'));
      if (result.kind !== 'ok') return messageFor(result, 'Wrong email or password');
      const me = await request('GET', '/api/me');
      onSuccess(me.kind === 'ok' ? me.data.email : field('email').trim().toLowerCase());
      return null;
    },
    async register() {
      await request('GET', '/api/me');
      const result = await request('POST', '/api/auth/register',
        { name: field('name'), email: field('email'), password: field('password'), inviteCode: field('inviteCode') });
      if (result.kind !== 'ok') return messageFor(result, 'Could not create the account');
      onSuccess(result.data.email);
      return null;
    },
    async forgot() {
      await request('GET', '/api/me');
      const result = await request('POST', '/api/auth/forgot', { email: field('email') });
      if (result.kind !== 'ok') return messageFor(result, 'Could not send the email');
      form.outerHTML = `<p class="notice">${esc(result.data.message)} Check your spam folder too.</p>`;
      return null;
    },
    async reset() {
      await request('GET', '/api/me');
      const result = await request('POST', '/api/auth/reset', { token, password: field('password') });
      if (result.kind !== 'ok') return messageFor(result, 'Could not change the password');
      go({ mode: 'login', notice: 'Password changed. Sign in.', email: result.data.email });
      return null;
    },
  };
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    error.hidden = true;
    const message = await submits[mode]();
    if (!form.isConnected) return; // the screen moved on
    button.disabled = false;
    if (message) {
      error.textContent = message;
      error.hidden = false;
    }
  });
}
