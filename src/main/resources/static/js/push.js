import { request } from './api.js';
import { updatePrefs } from './prefs.js';

function isStandalone() {
  return window.navigator.standalone === true || window.matchMedia('(display-mode: standalone)').matches;
}

function pushSupported() {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

function base64UrlToBytes(value) {
  const padded = value + '='.repeat((4 - (value.length % 4)) % 4);
  const raw = atob(padded.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}

/** Must be called from a tap: iOS only shows the permission prompt in response to one. */
export async function enableAlerts() {
  if (!isStandalone() || !pushSupported()) {
    return { ok: false, message: 'Add the app to your Home Screen first (iOS 16.4 or later), then open it from there.' };
  }
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    return { ok: false, message: 'Notifications are off. Allow them in the iPhone Settings app under Notifications › Gym.' };
  }
  const key = await request('GET', '/api/push/public-key');
  if (key.kind !== 'ok') return { ok: false, message: 'No connection. Try again when you have signal.' };
  try {
    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: base64UrlToBytes(key.data.publicKey),
    });
    const { endpoint, keys } = subscription.toJSON();
    const saved = await request('PUT', '/api/push/subscription', { endpoint, keys: { p256dh: keys.p256dh, auth: keys.auth } });
    if (saved.kind !== 'ok') return { ok: false, message: 'Could not save the alert settings. Try again.' };
  } catch {
    return { ok: false, message: 'Could not turn on alerts on this device.' };
  }
  updatePrefs({ alertsEnabled: true });
  return { ok: true };
}

/** Stops this phone's lock-screen alerts for the signed-in account. Best effort: never throws. */
export async function disableAlerts() {
  try {
    if (pushSupported()) {
      const registration = await navigator.serviceWorker.getRegistration();
      const subscription = await registration?.pushManager.getSubscription();
      if (subscription) {
        await request('DELETE', '/api/push/subscription', { endpoint: subscription.endpoint });
        await subscription.unsubscribe();
      }
    }
  } catch {
    // the server forgets dead subscriptions on its own when a push fails
  }
  updatePrefs({ alertsEnabled: false });
}
