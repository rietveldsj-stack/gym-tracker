const KEY = 'gt.prefs.v1';
const DEFAULTS = { restSeconds: 90, autoStart: true, alertsEnabled: false };

export function getPrefs() {
  try {
    return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) || '{}') };
  } catch {
    return { ...DEFAULTS };
  }
}

export function updatePrefs(patch) {
  const next = { ...getPrefs(), ...patch };
  try {
    localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    // Storage blocked: the change lasts until the app is closed.
  }
  return next;
}
