function csrfToken() {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : '';
}

/**
 * Result kinds: ok | network (no connection or server error, try again later) | unauthorized (401)
 * | forbidden (403, usually a missing CSRF cookie) | rejected (other 4xx, with the server's message).
 */
export async function request(method, path, body, { form = false } = {}) {
  const headers = {};
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken();
  let payload;
  if (body !== undefined) {
    headers['Content-Type'] = form ? 'application/x-www-form-urlencoded' : 'application/json';
    payload = form ? new URLSearchParams(body).toString() : JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(path, { method, headers, body: payload, credentials: 'same-origin', cache: 'no-store' });
  } catch {
    return { kind: 'network' };
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
  if (response.status === 403) return { kind: 'forbidden' };
  if (response.status >= 400 && response.status < 500) {
    return { kind: 'rejected', status: response.status, message: data?.message || `Request failed (${response.status})` };
  }
  return { kind: 'network' };
}

export function login(username, password) {
  return request('POST', '/login', { username, password }, { form: true });
}
