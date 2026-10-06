import { getPrefs, updatePrefs } from './prefs.js';

export const THEMES = [
  { id: 'blue', label: 'Blue' },
  { id: 'pink', label: 'Pink' },
  { id: 'redblack', label: 'Red & black' },
];

// Status-bar colour per theme. Blue keeps index.html's light/dark pair.
const BAR = { pink: '#fff8fa', redblack: '#161314' };
const BLUE_BARS = ['#fcfcfb', '#1a1a19'];

export function applyTheme(theme = getPrefs().theme) {
  const id = THEMES.some((t) => t.id === theme) ? theme : 'blue';
  if (id === 'blue') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = id;
  document.querySelectorAll('meta[name="theme-color"]').forEach((meta, i) => {
    meta.content = BAR[id] ?? BLUE_BARS[i];
  });
  window.dispatchEvent(new Event('gt:theme')); // screens redraw, so charts pick up the new colours
}

export function setTheme(theme) {
  updatePrefs({ theme });
  applyTheme(theme);
}
