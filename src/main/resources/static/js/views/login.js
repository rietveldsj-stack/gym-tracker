import { login, request } from '../api.js';

export function renderLogin(container, { onSuccess }) {
  container.innerHTML = `
    <section class="screen login">
      <h1>Gym Tracker</h1>
      <form class="stack" id="login-form">
        <label class="field">Username
          <input name="username" autocomplete="username" autocapitalize="none" autocorrect="off" required>
        </label>
        <label class="field">Password
          <input name="password" type="password" autocomplete="current-password" required>
        </label>
        <p class="error" id="login-error" hidden></p>
        <button class="btn primary big" type="submit">Sign in</button>
      </form>
    </section>`;
  const form = container.querySelector('#login-form');
  const error = container.querySelector('#login-error');
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const button = form.querySelector('button');
    button.disabled = true;
    error.hidden = true;
    await request('GET', '/api/me'); // makes sure the CSRF cookie exists
    const data = new FormData(form);
    const result = await login(String(data.get('username')).trim(), String(data.get('password')));
    button.disabled = false;
    if (result.kind === 'ok') {
      onSuccess();
      return;
    }
    error.textContent = result.kind === 'network' ? 'No connection. Try again when you have signal.' : 'Wrong username or password';
    error.hidden = false;
  });
}
