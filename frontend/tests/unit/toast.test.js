import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { toasts, toast, dismiss } from '../../src/stores/toast';

describe('toast store', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    toasts.splice(0, toasts.length);
  });

  afterEach(() => {
    vi.useRealTimers();
    toasts.splice(0, toasts.length);
  });

  it('默认以 info 展示并自动消失', () => {
    toast('已保存');
    expect(toasts).toHaveLength(1);
    expect(toasts[0]).toMatchObject({ message: '已保存', type: 'info' });
    vi.advanceTimersByTime(3500);
    expect(toasts).toHaveLength(0);
  });

  it('错误提示保留 7 秒', () => {
    toast('保存失败', 'error');
    vi.advanceTimersByTime(6999);
    expect(toasts).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(toasts).toHaveLength(0);
  });

  it('非字符串消息被转换', () => {
    toast(42);
    expect(toasts[0].message).toBe('42');
  });

  it('多条提示 ID 唯一且可单独关闭', () => {
    toast('a');
    toast('b');
    expect(toasts).toHaveLength(2);
    expect(toasts[0].id).not.toBe(toasts[1].id);
    dismiss(toasts[0].id);
    expect(toasts).toHaveLength(1);
    expect(toasts[0].message).toBe('b');
  });

  it('关闭不存在的 ID 无副作用', () => {
    toast('a');
    dismiss(999999);
    expect(toasts).toHaveLength(1);
  });
});
