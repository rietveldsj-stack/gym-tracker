import { request } from './api.js';
import { skipRest } from './rest.js';
import { disableAlerts } from './push.js';
import { forgetAccount } from './account.js';

const OFFLINE = "You're offline. Connect to the internet to log out.";
let signedOutHandler = () => {};

export function onSignedOut(fn) {
  signedOutHandler = fn;
}

/**
 * Signs this phone out. Only the server can cancel the sign-in cookie, so nothing changes until it answers: when
 * offline, the phone stays signed in with its data.
 */
export async function logout() {
  const me = await request('GET', '/api/me');
  if (me.kind === 'network') return { ok: false, message: OFFLINE };
  skipRest(); // also cancels the lock-screen alert that is counting down
  await disableAlerts();
  const result = await request('POST', '/logout');
  if (result.kind === 'network') return { ok: false, message: OFFLINE };
  if (result.kind !== 'ok') return { ok: false, message: 'Could not log out. Try again.' };
  signedOutHandler(); // shows the sign-in screen first, so wiping the data below doesn't redraw the app
  forgetAccount();
  return { ok: true };
}
