const MONTH_DAY = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric' });

function toMillis(value) {
  if (typeof value === 'number') return value;
  if (!value) return 0;
  const parsed = Date.parse(String(value));
  return Number.isNaN(parsed) ? 0 : parsed;
}

export function formatTimestamp(value) {
  const millis = toMillis(value);
  if (!millis) return '—';
  const date = new Date(millis);
  const pad = (part) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}`;
}

export function formatRelative(value, now = Date.now()) {
  const millis = toMillis(value);
  if (!millis) return '—';
  const diff = Math.max(0, now - millis);
  const minute = 60_000;
  const hour = 3_600_000;
  const day = 86_400_000;
  if (diff < minute) return '刚刚';
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`;
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`;
  if (diff < 7 * day) return `${Math.floor(diff / day)} 天前`;
  const date = new Date(millis);
  if (date.getFullYear() === new Date(now).getFullYear()) return MONTH_DAY.format(date);
  return formatTimestamp(millis).slice(0, 10);
}

export function formatSize(bytes) {
  const value = Number(bytes);
  if (!Number.isFinite(value)) return '—';
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

export function formatMillis(value) {
  const millis = Number(value);
  return Number.isFinite(millis) && millis > 0 ? `${millis} ms` : '—';
}

export function formatRatio(value) {
  const ratio = Number(value);
  return Number.isFinite(ratio) ? `${(ratio * 100).toFixed(0)}%` : '—';
}

export function formatCount(value) {
  const count = Number(value);
  return Number.isFinite(count) ? count.toLocaleString('zh-CN') : '—';
}

export function fileUrl(id, path) {
  const encoded = String(path).split('/').map(encodeURIComponent).join('/');
  return `/api/admin/skills/${encodeURIComponent(id)}/files/${encoded}`;
}
