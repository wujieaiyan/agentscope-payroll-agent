/** 与后端 SkillContentRules 对齐的正文门槛，编辑器与创建弹窗共用。 */
export const MIN_CONTENT_LENGTH = 200;
export const MAX_CONTENT_LENGTH = 200_000;
export const MAX_DESCRIPTION_LENGTH = 200;
export const ROLE_KEYWORDS = ['规则', '步骤', '流程', '输出', '要求', '职责', '角色'];

const HEADING_PATTERN = /^#{1,3}\s+\S+/m;

/** 与后端 SandboxGateway.SkillData 枚举对齐：UNKNOWN/INITIALIZING/READY/FAILED/BUSY/UNAVAILABLE/INVALID。 */
export const DATA_STATUS_KEYS = {
  READY: 'READY',
  INITIALIZING: 'INITIALIZING',
  BUSY: 'BUSY',
  FAILED: 'FAILED',
  UNAVAILABLE: 'UNAVAILABLE',
  INVALID: 'INVALID',
  UNKNOWN: 'UNKNOWN',
};

const BUSY_STATUSES = [DATA_STATUS_KEYS.INITIALIZING, DATA_STATUS_KEYS.BUSY];
const BROKEN_STATUSES = [
  DATA_STATUS_KEYS.FAILED,
  DATA_STATUS_KEYS.UNAVAILABLE,
  DATA_STATUS_KEYS.INVALID,
];

/**
 * 数据状态 → 文案/色调/tooltip 的唯一映射，列表、概览、数据页共用。
 * needsData=false 派生「无需数据」；needsData=null（Nacos）派生「数据未知」。
 */
export function dataStatusMeta({ needsData = null, dataState = null, sandboxEnabled = true } = {}) {
  if (!sandboxEnabled) {
    return {
      key: 'disabled',
      label: '沙箱未启用',
      tone: 'dim',
      tooltip: '未启用沙箱，数据初始化与脚本执行不可用',
    };
  }
  if (needsData === false) {
    return { key: 'none', label: '无需数据', tone: 'neutral', tooltip: '该技能不依赖初始化数据' };
  }
  if (needsData === null || needsData === undefined) {
    return { key: 'unknown', label: '数据未知', tone: 'dim', tooltip: '当前来源不提供数据状态' };
  }
  const status = dataState?.status || DATA_STATUS_KEYS.UNKNOWN;
  if (status === DATA_STATUS_KEYS.READY) {
    return { key: 'ready', label: '数据就绪', tone: 'ok', tooltip: '数据已初始化，可直接使用' };
  }
  if (BUSY_STATUSES.includes(status)) {
    return { key: 'initializing', label: '初始化中', tone: 'warn', tooltip: '正在初始化数据' };
  }
  if (BROKEN_STATUSES.includes(status)) {
    return {
      key: 'attention',
      label: '初始化失败',
      tone: 'danger',
      tooltip: dataState?.message || '数据初始化失败，需处理',
    };
  }
  return { key: 'uninitialized', label: '未初始化', tone: 'warn', tooltip: '尚未初始化数据' };
}

export { formatRelative } from '../../../utils/format.js';

/** 后端 users 字典（username → displayName）解析展示名。 */
export function displayUser(username, users = {}) {
  if (!username) return '';
  return users?.[username] || username;
}

export function contentChecks(content = '', description = '') {
  const body = String(content || '').trim();
  return [
    { key: 'heading', label: '含 Markdown 标题', ok: HEADING_PATTERN.test(body) },
    {
      key: 'role',
      label: '含角色/规则类关键词',
      ok: ROLE_KEYWORDS.some((word) => body.includes(word)),
    },
    { key: 'min', label: `正文至少 ${MIN_CONTENT_LENGTH} 字`, ok: body.length >= MIN_CONTENT_LENGTH },
    {
      key: 'max',
      label: `正文不超过 ${MAX_CONTENT_LENGTH.toLocaleString('zh-CN')} 字`,
      ok: body.length <= MAX_CONTENT_LENGTH,
    },
    {
      key: 'desc',
      label: `说明不超过 ${MAX_DESCRIPTION_LENGTH} 字`,
      ok: String(description || '').length <= MAX_DESCRIPTION_LENGTH,
    },
  ];
}

function lineCount(value) {
  if (!value) return 0;
  const lines = String(value).split('\n');
  if (lines[lines.length - 1] === '') lines.pop();
  return lines.length;
}

/** `diffLines` 结果 → `{added, removed}`；编辑器对比与保存条共用，避免两处各算一遍。 */
export function diffSummary(changes = []) {
  let added = 0;
  let removed = 0;
  for (const part of changes || []) {
    if (part?.added) added += lineCount(part.value);
    else if (part?.removed) removed += lineCount(part.value);
  }
  return { added, removed };
}
