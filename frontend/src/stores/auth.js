import { reactive } from 'vue';
import { api } from '../api/http';

export const auth = reactive({
  loaded: false,
  user: null,
  authEnabled: false,
});

export async function loadMe() {
  try {
    const state = await api('/api/auth/me');
    auth.user = state ? state.user : null;
    auth.authEnabled = Boolean(state && state.auth_enabled);
  } catch (error) {
    auth.user = null;
  } finally {
    auth.loaded = true;
  }
  return auth.user;
}

export async function login(username, password) {
  const state = await api('/api/auth/login', {
    method: 'POST',
    body: { username, password },
  });
  auth.user = state.user;
  auth.authEnabled = Boolean(state.auth_enabled);
  return state.user;
}

export async function logout() {
  try {
    await api('/api/auth/logout', { method: 'POST' });
  } finally {
    auth.user = null;
  }
}

export function can(permission) {
  return Boolean(auth.user && Array.isArray(auth.user.permissions) && auth.user.permissions.includes(permission));
}
