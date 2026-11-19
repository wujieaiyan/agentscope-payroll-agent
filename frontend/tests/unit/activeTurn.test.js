import { describe, it, expect, beforeEach } from 'vitest';
import { saveActiveTurn, loadActiveTurn, clearActiveTurn } from '../../src/stores/activeTurn';

const SESSION = 'session-1';
const KEY = `payroll.activeTurn.${SESSION}`;

describe('activeTurn store', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it('往返读写轮次与序号', () => {
    saveActiveTurn(SESSION, { turnId: 'turn-9', lastSeq: 12 });
    const restored = loadActiveTurn(SESSION);
    expect(restored.turnId).toBe('turn-9');
    expect(restored.lastSeq).toBe(12);
    expect(restored.startedAt).toBeGreaterThan(0);
  });

  it('缺少会话或轮次时不写入', () => {
    saveActiveTurn('', { turnId: 'turn-1', lastSeq: 0 });
    saveActiveTurn(SESSION, { lastSeq: 3 });
    expect(window.sessionStorage.length).toBe(0);
  });

  it('超过 24 小时的记录视为过期并清理', () => {
    window.sessionStorage.setItem(
      KEY,
      JSON.stringify({
        turnId: 'turn-old',
        lastSeq: 4,
        startedAt: Date.now() - 25 * 60 * 60 * 1000,
      })
    );
    expect(loadActiveTurn(SESSION)).toBeNull();
    expect(window.sessionStorage.getItem(KEY)).toBeNull();
  });

  it('损坏的 JSON 返回 null', () => {
    window.sessionStorage.setItem(KEY, '{not-json');
    expect(loadActiveTurn(SESSION)).toBeNull();
  });

  it('负序号归一为 0', () => {
    saveActiveTurn(SESSION, { turnId: 'turn-1', lastSeq: -5 });
    expect(loadActiveTurn(SESSION).lastSeq).toBe(0);
  });

  it('清理时轮次不匹配则保留记录', () => {
    saveActiveTurn(SESSION, { turnId: 'turn-a', lastSeq: 1 });
    clearActiveTurn(SESSION, 'turn-b');
    expect(loadActiveTurn(SESSION).turnId).toBe('turn-a');
    clearActiveTurn(SESSION, 'turn-a');
    expect(loadActiveTurn(SESSION)).toBeNull();
  });
});
