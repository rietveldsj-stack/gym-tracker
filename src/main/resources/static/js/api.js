function csrfToken() {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : '';
}

const TIMEOUT_MS = 10000;

// Every API call goes through one queue, one at a time. When the server session is gone (iOS dropped the cookie,
// or a deploy restarted the server) the first call logs in again with the single-use remember-me token; a second
// call racing it would present the old token, trip Spring's cookie-theft check and sign the user out.
let queue = Promise.resolve();

// The sign-in forms fetch a CSRF cookie before they post, so a 403 there is the server's answer (like a wrong
// invite code), not a missing cookie.
const AUTH_PATHS = /^\/(login|logout|api\/auth\/)/;

/**
 * Result kinds: ok | network (no connection, timeout or server error: try again later) | unauthorized (401)
 * | forbidden (403: a missing CSRF cookie, or the server's refusal with a message)
 * | rejected (other 4xx, with the server's message).
 */
export function request(method, path, body, options = {}) {
  const run = queue.then(async () => {
    let result = await send(method, path, body, options);
    if (result.kind === 'forbidden' && method !== 'GET' && !AUTH_PATHS.test(path)) {
      // Missing or stale CSRF cookie: fetch a fresh one and retry once.
      await send('GET', '/api/me');
      result = await send(method, path, body, options);
    }
    return result;
  });
  queue = run.catch(() => {});
  return run;
}

async function send(method, path, body, { form = false } = {}) {
  const headers = {};
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken();
  let payload;
  if (body !== undefined) {
    headers['Content-Type'] = form ? 'application/x-www-form-urlencoded' : 'application/json';
    payload = form ? new URLSearchParams(body).toString() : JSON.stringify(body);
  }
  let response;
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), TIMEOUT_MS); // weak signal must not hang the app or the outbox
  try {
    response = await fetch(path, { method, headers, body: payload, credentials: 'same-origin', cache: 'no-store', signal: abort.signal });
  } catch {
    return { kind: 'network' };
  } finally {
    clearTimeout(timer);
  }
  const text = await response.text().catch(() => '');
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (response.ok) return { kind: 'ok', status: response.status, data };
  if (response.status === 401) return { kind: 'unauthorized', message: data?.message };
  if (response.status === 403) return { kind: 'forbidden', message: data?.message };
  if (response.status >= 400 && response.status < 500) {
    return { kind: 'rejected', status: response.status, message: data?.message || `Request failed (${response.status})` };
  }
  return { kind: 'network' };
}

export function login(username, password) {
  return request('POST', '/login', { username, password }, { form: true });
}
