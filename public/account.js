let session = { status: 'loading', user: null, csrfToken: null, features: { database: false, googleSignIn: false }, limits: {}, notice: null };
const listeners = new Set();

export class ApiError extends Error {
  constructor(status, message, code) { super(message); this.status = status; this.code = code; }
}

function update(value, reason) {
  const previous = session;
  session = { ...session, ...value };
  for (const listener of listeners) listener(session, previous, reason);
}

export const sessionInfo = () => session;
export const signedIn = () => session.status === 'ready' && session.user !== null;
export const onSessionChange = listener => { listeners.add(listener); return () => listeners.delete(listener); };

export async function apiRequest(path, { method = 'GET', data, signal } = {}) {
  if (!path.startsWith('/api/') || path.startsWith('//')) throw new Error('Use a FutureCode API path.');
  const writing = !['GET', 'HEAD'].includes(method);
  const response = await fetch(path, {
    method, signal, credentials: 'same-origin', cache: 'no-store',
    headers: {
      Accept: 'application/json',
      ...(writing ? { 'Content-Type': 'application/json', ...(session.csrfToken ? { 'X-CSRF-Token': session.csrfToken } : {}) } : {})
    },
    ...(data === undefined ? {} : { body: JSON.stringify(data) })
  });
  let result;
  try { result = await response.json(); }
  catch { throw new ApiError(response.status, 'FutureCode returned unreadable data. Retry or check the server setup.', 'invalid_response'); }
  if (!response.ok) {
    if ((response.status === 401 || result.code === 'csrf_failed') && session.user) update({ status: 'expired', csrfToken: null, notice: 'Your session changed or expired. Download unsaved work before signing in again, or refresh this session.' }, 'expired');
    throw new ApiError(response.status, typeof result.error === 'string' ? result.error : 'The request could not be completed.', result.code || 'request_failed');
  }
  return result;
}

export async function refreshSession(reason = 'refresh') {
  try {
    const value = await apiRequest('/api/session');
    update({ ...value, status: 'ready' }, reason);
    return session;
  } catch (error) {
    update({ status: 'error', notice: error.message }, 'unavailable');
    return session;
  }
}

export function requireAccount() {
  if (signedIn()) return true;
  if (session.status === 'loading') {
    window.dispatchEvent(new CustomEvent('futurecode-session-loading'));
    return false;
  }
  window.dispatchEvent(new CustomEvent('futurecode-signin-required'));
  return false;
}

export async function googleSignIn(returnTo = 'account') {
  if (!session.features.googleSignIn) throw new ApiError(503, 'Google sign-in will be available after MySQL and OAuth are configured. You can browse the collection now.', 'sign_in_unavailable');
  if (session.origin && session.origin !== location.origin) throw new ApiError(400, `Open the configured FutureCode address before signing in: ${session.origin}`, 'origin_mismatch');
  const result = await apiRequest('/api/auth/google', { method: 'POST', data: { returnTo } });
  const destination = new URL(result.url);
  if (destination.origin !== 'https://accounts.google.com') throw new Error('The sign-in destination is invalid.');
  window.location.assign(destination.href);
}

export async function signOut() {
  await apiRequest('/api/auth/logout', { method: 'POST', data: {} });
  update({ user: null, csrfToken: null }, 'logout');
}

export function accountDeleted() { update({ user: null, csrfToken: null }, 'account-deleted'); }
