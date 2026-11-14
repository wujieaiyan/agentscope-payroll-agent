const PREFIX = 'payroll.activeTurn.';
const MAX_AGE_MS = 24 * 60 * 60 * 1000;

function key(sessionId) {
  return `${PREFIX}${sessionId}`;
}

export function saveActiveTurn(sessionId, state) {
  if (!sessionId || !state || !state.turnId) return;
  try {
    window.sessionStorage.setItem(
      key(sessionId),
      JSON.stringify({
        turnId: state.turnId,
        lastSeq: Math.max(0, Number(state.lastSeq) || 0),
        startedAt: state.startedAt || Date.now(),
      })
    );
  } catch (error) {
    // 隐私模式或存储被禁用时仅失去刷新恢复能力，不影响正常流式回答
  }
}

export function loadActiveTurn(sessionId) {
  if (!sessionId) return null;
  try {
    const raw = window.sessionStorage.getItem(key(sessionId));
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || !parsed.turnId) return null;
    if (parsed.startedAt && Date.now() - parsed.startedAt > MAX_AGE_MS) {
      window.sessionStorage.removeItem(key(sessionId));
      return null;
    }
    return {
      turnId: parsed.turnId,
      lastSeq: Math.max(0, Number(parsed.lastSeq) || 0),
      startedAt: parsed.startedAt || 0,
    };
  } catch (error) {
    return null;
  }
}

export function clearActiveTurn(sessionId, turnId) {
  if (!sessionId) return;
  try {
    const stored = loadActiveTurn(sessionId);
    if (turnId && stored && stored.turnId !== turnId) return;
    window.sessionStorage.removeItem(key(sessionId));
  } catch (error) {
    // 忽略存储异常
  }
}
