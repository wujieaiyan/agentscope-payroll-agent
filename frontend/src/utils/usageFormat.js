/** 用量看板数字与时间格式：万/亿缩写、紧凑时间与百分比。 */

export function compactNumber(value) {
  const n = Number(value || 0);
  if (!Number.isFinite(n)) return '0';
  const abs = Math.abs(n);
  if (abs >= 1e8) return `${trimZero(n / 1e8)} 亿`;
  if (abs >= 1e4) return `${trimZero(n / 1e4)} 万`;
  return n.toLocaleString('zh-CN');
}

export function fullNumber(value) {
  const n = Number(value || 0);
  return Number.isFinite(n) ? n.toLocaleString('zh-CN') : '0';
}

export function percent(part, total) {
  const sum = Number(total || 0);
  if (!sum) return '0%';
  return `${Math.round((Number(part || 0) / sum) * 100)}%`;
}

export function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—';
}

export function formatBucket(value, granularity) {
  if (!value) return '';
  const text = String(value);
  const time = text.includes('T') ? text.slice(11, 16) : '';
  if (granularity === 'hour') {
    return time || text.slice(11, 16);
  }
  return text.slice(5, 10);
}

function trimZero(value) {
  return Number(value.toFixed(value >= 100 ? 0 : 1)).toString();
}
