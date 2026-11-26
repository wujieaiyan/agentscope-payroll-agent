import { ref } from 'vue';
import { api } from '../api/http';

const state = ref(null);
let pending = null;

function describe(payload) {
  const location = String(payload?.location || '');
  const source = location.startsWith('db') ? 'db' : location.toLowerCase().includes('nacos') ? 'nacos' : '';
  return {
    source,
    writable: Boolean(payload?.writable),
    location,
    label: source === 'db' ? 'db 源' : source === 'nacos' ? 'Nacos' : '来源未知',
  };
}

export function useSkillEnv() {
  return state;
}

export function loadSkillEnv() {
  if (state.value || pending) return pending || Promise.resolve(state.value);
  pending = api('/api/admin/skills?size=1')
    .then((payload) => {
      state.value = describe(payload);
    })
    .catch(() => {
      state.value = null;
    })
    .finally(() => {
      pending = null;
    });
  return pending;
}
