import { getPrefs, updatePrefs } from './prefs.js';

export const THEMES = [
  { id: 'blue', label: 'Blue' },
  { id: 'pink', label: 'Pink' },
  { id: 'redblack', label: 'Red & black' },
];

// Status-bar colour per theme. Blue keeps index.html's light/dark pair.
const BAR = { pink: '#fff8fa', redblack: '#161314' };
const BLUE_BARS = ['#fcfcfb', '#1a1a19'];

const themeId = (theme) => (THEMES.some((t) => t.id === theme) ? theme : 'blue');

/**
 * The app icon in the current theme's colours. iPhone copies the home-screen icon once, when the app is added to the
 * Home Screen, so it is offered here and in index.html: adding the app gives the icon of the theme that is on.
 */
export function appIconUrl(theme = getPrefs().theme) {
  const id = themeId(theme);
  return `/icons/apple-touch-icon${id === 'blue' ? '' : `-${id}`}.png`;
}

export function applyTheme(theme = getPrefs().theme) {
  const id = themeId(theme);
  if (id === 'blue') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = id;
  document.querySelectorAll('meta[name="theme-color"]').forEach((meta, i) => {
    meta.content = BAR[id] ?? BLUE_BARS[i];
  });
  document.querySelector('link[rel="apple-touch-icon"]')?.setAttribute('href', appIconUrl(id));
  document.querySelectorAll('img.app-icon').forEach((img) => img.setAttribute('src', appIconUrl(id)));
  window.dispatchEvent(new Event('gt:theme')); // screens redraw, so charts pick up the new colours
}

export function setTheme(theme) {
  updatePrefs({ theme });
  applyTheme(theme);
}
