let route = { tab: 'workout' };
let renderer = () => {};

export function setRenderer(fn) {
  renderer = fn;
}

export function getRoute() {
  return route;
}

export function navigate(next) {
  route = next;
  renderer();
  window.scrollTo(0, 0);
}
