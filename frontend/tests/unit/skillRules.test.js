import { describe, expect, it } from 'vitest';
import { dataStatusMeta, diffSummary, displayUser } from '../../src/views/admin/skills/rules';
import { clearDraft, draftKey, readDraft, writeDraft } from '../../src/views/admin/skills/draft';

function memoryStorage() {
  const map = new Map();
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: (key) => map.delete(key),
  };
}

describe('displayUser', () => {
  it('优先取 users 字典的展示名，缺省回落用户名', () => {
    expect(displayUser('wangmin', { wangmin: '王敏' })).toBe('王敏');
    expect(displayUser('wangmin', {})).toBe('wangmin');
    expect(displayUser('wangmin')).toBe('wangmin');
  });

  it('空用户名返回空串', () => {
    expect(displayUser('', { a: 'A' })).toBe('');
    expect(displayUser(null)).toBe('');
    expect(displayUser(undefined)).toBe('');
  });
});

describe('diffSummary', () => {
  it('按行累加新增与删除，忽略未变更块', () => {
    const changes = [
      { value: 'a\nb\n', added: true },
      { value: 'c\n', removed: true },
      { value: 'same\n' },
    ];
    expect(diffSummary(changes)).toEqual({ added: 2, removed: 1 });
  });

  it('空输入返回零', () => {
    expect(diffSummary()).toEqual({ added: 0, removed: 0 });
    expect(diffSummary([])).toEqual({ added: 0, removed: 0 });
  });

  it('末尾换行不额外计一行', () => {
    expect(diffSummary([{ value: 'only\n', added: true }])).toEqual({ added: 1, removed: 0 });
  });
});

describe('dataStatusMeta', () => {
  it('沙箱未启用优先于其它状态', () => {
    expect(dataStatusMeta({ sandboxEnabled: false, needsData: false }).key).toBe('disabled');
  });

  it('needsData 三态派生无需数据 / 数据未知', () => {
    expect(dataStatusMeta({ needsData: false }).key).toBe('none');
    expect(dataStatusMeta({ needsData: null }).key).toBe('unknown');
    expect(dataStatusMeta({ needsData: undefined }).key).toBe('unknown');
  });

  it('按 SandboxGateway 状态映射就绪 / 初始化中 / 失败 / 未初始化', () => {
    expect(dataStatusMeta({ needsData: true, dataState: { status: 'READY' } }).key).toBe('ready');
    expect(dataStatusMeta({ needsData: true, dataState: { status: 'INITIALIZING' } }).key).toBe(
      'initializing'
    );
    expect(dataStatusMeta({ needsData: true, dataState: { status: 'BUSY' } }).key).toBe(
      'initializing'
    );
    const failed = dataStatusMeta({
      needsData: true,
      dataState: { status: 'FAILED', message: 'pip 退出码 1' },
    });
    expect(failed.key).toBe('attention');
    expect(failed.tooltip).toBe('pip 退出码 1');
    expect(dataStatusMeta({ needsData: true, dataState: { status: 'UNKNOWN' } }).key).toBe(
      'uninitialized'
    );
  });
});

describe('draft storage', () => {
  it('按技能 ID 写入并读回，写入自带 savedAt', () => {
    const storage = memoryStorage();
    writeDraft(storage, 'poetry-notes', { content: '正文', form: { description: '说明' } });
    const draft = readDraft(storage, 'poetry-notes');
    expect(draft.content).toBe('正文');
    expect(draft.form).toEqual({ description: '说明' });
    expect(Number.isFinite(draft.savedAt)).toBe(true);
    expect(draftKey('poetry-notes')).toBe('skill.draft.poetry-notes');
  });

  it('新建用 new 作为键', () => {
    const storage = memoryStorage();
    writeDraft(storage, '', { content: 'x' });
    expect(readDraft(storage, '')).not.toBeNull();
    expect(storage.getItem('skill.draft.new')).not.toBeNull();
  });

  it('超过 14 天的草稿读回时清掉', () => {
    const storage = memoryStorage();
    const stale = Date.now() - 15 * 24 * 60 * 60 * 1000;
    storage.setItem(draftKey('old'), JSON.stringify({ content: 'x', savedAt: stale }));
    expect(readDraft(storage, 'old')).toBeNull();
    expect(storage.getItem(draftKey('old'))).toBeNull();
  });

  it('损坏数据与缺失 storage 都安全降级', () => {
    const storage = memoryStorage();
    storage.setItem(draftKey('broken'), '{not json');
    expect(readDraft(storage, 'broken')).toBeNull();
    expect(readDraft(null, 'anything')).toBeNull();
    expect(() => writeDraft(null, 'x', { content: 'y' })).not.toThrow();
    expect(() => clearDraft(null, 'x')).not.toThrow();
  });

  it('clearDraft 删除对应键', () => {
    const storage = memoryStorage();
    writeDraft(storage, 'gone', { content: 'x' });
    clearDraft(storage, 'gone');
    expect(readDraft(storage, 'gone')).toBeNull();
  });
});
