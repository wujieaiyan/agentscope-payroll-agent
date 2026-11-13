import { reactive } from 'vue';

let seq = 0;
export const toasts = reactive([]);

export function toast(message, type = 'info', timeout = 3500) {
  const id = ++seq;
  toasts.push({ id, message: String(message), type });
  const ms = type === 'error' ? 7000 : timeout;
  setTimeout(() => dismiss(id), ms);
}

export function dismiss(id) {
  const index = toasts.findIndex((item) => item.id === id);
  if (index >= 0) toasts.splice(index, 1);
}
