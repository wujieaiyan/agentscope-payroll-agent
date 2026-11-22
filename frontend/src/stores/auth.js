import { reactive } from 'vue';
import { api } from '../api/http';

export const auth = reactive({
  loaded: false,
  user: null,
  authEnabled: false,
  profileEnabled: false,
  mustChangePassword: false,
});

export async function loadMe() {
  try {
    const state = await api('/api/auth/me');
    auth.user = state ? state.user : null;
    auth.authEnabled = Boolean(state && state.auth_enabled);
    auth.profileEnabled = Boolean(state && state.profile_enabled);
    auth.mustChangePassword = Boolean(state && state.must_change_password);
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
  auth.profileEnabled = Boolean(state.profile_enabled);
  auth.mustChangePassword = Boolean(state.must_change_password);
  return state.user;
}

export function markPasswordChanged() {
  auth.mustChangePassword = false;
}

export async function logout() {
  try {
    await api('/api/auth/logout', { method: 'POST' });
  } finally {
    auth.user = null;
    auth.mustChangePassword = false;
  }
}

export function can(permission) {
  return Boolean(auth.user && Array.isArray(auth.user.permissions) && auth.user.permissions.includes(permission));
}
