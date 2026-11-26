/** 编辑器草稿：按技能 ID 存 localStorage，新建用 `new`；读写都走可注入的 storage 便于单测。 */
const PREFIX = 'skill.draft.';
const MAX_AGE_MS = 14 * 24 * 60 * 60 * 1000;

export function draftKey(skillId) {
  return `${PREFIX}${skillId || 'new'}`;
}

export function readDraft(storage, skillId) {
  if (!storage) return null;
  try {
    const raw = storage.getItem(draftKey(skillId));
    if (!raw) return null;
    const draft = JSON.parse(raw);
    if (!draft || typeof draft !== 'object') return null;
    if (!Number.isFinite(draft.savedAt)) return null;
    if (Date.now() - draft.savedAt > MAX_AGE_MS) {
      storage.removeItem(draftKey(skillId));
      return null;
    }
    return draft;
  } catch (caught) {
    return null;
  }
}

export function writeDraft(storage, skillId, draft) {
  if (!storage) return;
  try {
    storage.setItem(draftKey(skillId), JSON.stringify({ ...draft, savedAt: Date.now() }));
  } catch (caught) {
    // 配额满或隐私模式：草稿降级为仅本次会话有效，不影响编辑。
  }
}

export function clearDraft(storage, skillId) {
  if (!storage) return;
  try {
    storage.removeItem(draftKey(skillId));
  } catch (caught) {
    // 同上，清不掉也不阻塞保存流程。
  }
}
