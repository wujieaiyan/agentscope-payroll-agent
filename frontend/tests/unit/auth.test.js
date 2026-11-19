import { describe, it, expect, beforeEach, vi } from 'vitest';

vi.mock('../../src/api/http', () => ({ api: vi.fn() }));

import { api } from '../../src/api/http';
import { auth, loadMe, login, logout, can } from '../../src/stores/auth';

describe('auth store', () => {
  beforeEach(() => {
    auth.loaded = false;
    auth.user = null;
    auth.authEnabled = false;
    api.mockReset();
  });

  it('loadMe 成功后写入用户与开关状态', async () => {
    api.mockResolvedValue({ user: { username: 'alice', permissions: ['employee:read'] }, auth_enabled: true });
    const user = await loadMe();
    expect(user.username).toBe('alice');
    expect(auth.loaded).toBe(true);
    expect(auth.authEnabled).toBe(true);
    expect(api).toHaveBeenCalledWith('/api/auth/me');
  });

  it('loadMe 失败时清空用户但仍标记已加载', async () => {
    auth.user = { username: 'stale' };
    api.mockRejectedValue(new Error('boom'));
    const user = await loadMe();
    expect(user).toBeNull();
    expect(auth.user).toBeNull();
    expect(auth.loaded).toBe(true);
  });

  it('login 提交凭据并写入状态', async () => {
    api.mockResolvedValue({ user: { username: 'bob', permissions: [] }, auth_enabled: false });
    await login('bob', 'secret');
    expect(api).toHaveBeenCalledWith('/api/auth/login', {
      method: 'POST',
      body: { username: 'bob', password: 'secret' },
    });
    expect(auth.user.username).toBe('bob');
    expect(auth.authEnabled).toBe(false);
  });

  it('logout 接口失败时仍清空本地用户并向上抛出', async () => {
    auth.user = { username: 'bob' };
    api.mockRejectedValue(new Error('network'));
    await expect(logout()).rejects.toThrow('network');
    expect(auth.user).toBeNull();
  });

  it('can 依据权限列表判断', () => {
    expect(can('employee:read')).toBe(false);
    auth.user = { username: 'alice', permissions: ['employee:read'] };
    expect(can('employee:read')).toBe(true);
    expect(can('employee:write')).toBe(false);
    auth.user = { username: 'ghost' };
    expect(can('employee:read')).toBe(false);
  });
});
