const header = document.querySelector('.book-header');
const rail = document.getElementById('contents-rail');
const toggle = document.getElementById('contents-toggle');
const actions = document.getElementById('header-account-actions');
const mobileActions = document.getElementById('mobile-account-actions');
if (!header || !rail || !toggle || !actions || !mobileActions) throw new Error('The shared navigation shell is incomplete.');
const compact = window.matchMedia('(max-width: 760px)');

export function closeNavigation(restoreFocus = false) {
  toggle.setAttribute('aria-expanded', 'false');
  rail.classList.remove('contents-open');
  if (restoreFocus) toggle.focus({ preventScroll: true });
}
function synchronize() {
  const active = document.activeElement;
  const actionFocused = actions.contains(active), railFocused = rail.contains(active);
  const target = compact.matches ? mobileActions : header;
  if (actions.parentElement !== target) target.append(actions);
  closeNavigation();
  if (compact.matches && (actionFocused || railFocused)) toggle.focus({ preventScroll: true });
  else if (actionFocused) active.focus({ preventScroll: true });
  else if (!compact.matches && active === toggle) header.querySelector('.brand').focus({ preventScroll: true });
}
toggle.addEventListener('click', () => {
  const open = toggle.getAttribute('aria-expanded') !== 'true';
  toggle.setAttribute('aria-expanded', String(open)); rail.classList.toggle('contents-open', open);
  if (open) rail.querySelector('button:not([disabled]):not([hidden]), a[href]')?.focus({ preventScroll: true });
});
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && toggle.getAttribute('aria-expanded') === 'true') closeNavigation(true);
});
for (const link of document.querySelectorAll('.contents-rail a[href], .site-footer a[href], .book-header .brand')) {
  link.addEventListener('click', () => closeNavigation());
}
compact.addEventListener('change', synchronize);
synchronize();
toggle.disabled = false;
