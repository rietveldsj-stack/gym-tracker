import * as store from './store.js';
import { refreshAlerts } from './push.js';

// The data on this phone belongs to one account and is tagged with its email. When another account signs in, or
// the data has no tag (it is from before accounts existed), the data is wiped first: accounts never mix.
const KEY = 'gt.account.v1';

export function accountEmail() {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

/** Call with the signed-in account's email before its data is synced. */
export function useAccount(email) {
  if (accountEmail() === email) return;
  store.reset();
  try {
    localStorage.setItem(KEY, email);
    localStorage.removeItem('gt.knownUser'); // the single-user version's flag
  } catch {
    // Storage blocked: the data only lives in memory anyway.
  }
  refreshAlerts(); // this phone's alerts now belong to this account
}

/** After logging out: nothing of the account stays on the phone. */
export function forgetAccount() {
  store.reset();
  try {
    localStorage.removeItem(KEY);
  } catch {
    // ignore
  }
}
