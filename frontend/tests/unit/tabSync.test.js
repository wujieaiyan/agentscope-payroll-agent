import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { createTabSync } from '../../src/stores/tabSync';

class FakeBroadcastChannel {
  static channels = new Set();

  constructor(name) {
    this.name = name;
    this.listeners = new Set();
    FakeBroadcastChannel.channels.add(this);
  }

  addEventListener(type, listener) {
    if (type === 'message') this.listeners.add(listener);
  }

  postMessage(message) {
    const payload = JSON.parse(JSON.stringify(message));
    for (const channel of FakeBroadcastChannel.channels) {
      if (channel === this || channel.name !== this.name) continue;
      for (const listener of channel.listeners) listener({ data: payload });
    }
  }

  close() {
    this.listeners.clear();
    FakeBroadcastChannel.channels.delete(this);
  }

  static reset() {
    FakeBroadcastChannel.channels.clear();
  }
}

const SESSION = 'session-1';
const TURN = 'turn-1';

function makeOwner(snapshot) {
  const onEvent = vi.fn();
  const onEnd = vi.fn();
  const sync = createTabSync({
    snapshot,
    onEvent,
    onState: vi.fn(),
    onEnd,
  });
  return { sync, onEvent, onEnd };
}

function makeMirror() {
  const onEvent = vi.fn();
  const onState = vi.fn();
  const onEnd = vi.fn();
  const sync = createTabSync({ snapshot: () => null, onEvent, onState, onEnd });
  return { sync, onEvent, onState, onEnd };
}

describe('tabSync 多标签页协调', () => {
  beforeEach(() => {
    FakeBroadcastChannel.reset();
    window.BroadcastChannel = FakeBroadcastChannel;
  });

  afterEach(() => {
    vi.useRealTimers();
    delete window.BroadcastChannel;
    FakeBroadcastChannel.reset();
  });

  it('可用时报告可用并在关闭后清理', () => {
    const owner = makeOwner(() => ({ lastSeq: 0 }));
    expect(owner.sync.available()).toBe(true);
    owner.sync.close();
  });

  it('镜像标签页探测到主人后拉取快照', async () => {
    const owner = makeOwner(() => ({ lastSeq: 7, messages: [{ role: 'assistant' }] }));
    const mirror = makeMirror();
    owner.sync.claim(SESSION, TURN);

    const followed = await mirror.sync.follow(SESSION);

    expect(followed).toBe(true);
    expect(mirror.onState).toHaveBeenCalledTimes(1);
    const [sessionId, turnId, snapshot] = mirror.onState.mock.calls[0];
    expect(sessionId).toBe(SESSION);
    expect(turnId).toBe(TURN);
    expect(snapshot).toEqual({ lastSeq: 7, messages: [{ role: 'assistant' }] });

    owner.sync.close();
    mirror.sync.close();
  });

  it('没有主人时 follow 超时返回 false', async () => {
    vi.useFakeTimers();
    const mirror = makeMirror();
    const pending = mirror.sync.follow(SESSION);
    await vi.advanceTimersByTimeAsync(400);
    expect(await pending).toBe(false);
    expect(mirror.onState).not.toHaveBeenCalled();
    mirror.sync.close();
  });

  it('主人转发事件与终态给镜像', async () => {
    const owner = makeOwner(() => ({ lastSeq: 0 }));
    const mirror = makeMirror();
    owner.sync.claim(SESSION, TURN);
    await mirror.sync.follow(SESSION);

    owner.sync.event(SESSION, TURN, 'delta', { seq: 3, text: '你好' });
    expect(mirror.onEvent).toHaveBeenCalledWith(SESSION, TURN, 'delta', { seq: 3, text: '你好' });

    owner.sync.release(SESSION, TURN, { handoff: true });
    expect(mirror.onEnd).toHaveBeenCalledWith(SESSION, TURN, { handoff: true });

    owner.sync.close();
    mirror.sync.close();
  });

  it('关闭页面时以 handoff 通知镜像接管', () => {
    const owner = makeOwner(() => ({ lastSeq: 0 }));
    const mirror = makeMirror();
    owner.sync.claim(SESSION, TURN);

    owner.sync.close();

    expect(mirror.onEnd).toHaveBeenCalledWith(SESSION, TURN, { handoff: true });
    mirror.sync.close();
  });

  it('非主人不能转发事件或释放', async () => {
    const owner = makeOwner(() => ({ lastSeq: 0 }));
    const mirror = makeMirror();
    owner.sync.claim(SESSION, TURN);
    await mirror.sync.follow(SESSION);

    mirror.sync.event(SESSION, TURN, 'delta', { seq: 1 });
    mirror.sync.release(SESSION, TURN, {});
    expect(owner.onEvent).not.toHaveBeenCalled();
    expect(owner.onEnd).not.toHaveBeenCalled();

    owner.sync.close();
    mirror.sync.close();
  });

  it('浏览器不支持 BroadcastChannel 时退化为独立拉流', async () => {
    delete window.BroadcastChannel;
    const owner = makeOwner(() => ({ lastSeq: 0 }));
    const mirror = makeMirror();
    expect(owner.sync.available()).toBe(false);
    expect(await mirror.sync.follow(SESSION)).toBe(false);
    owner.sync.claim(SESSION, TURN);
    owner.sync.release(SESSION, TURN, {});
    expect(mirror.onEnd).not.toHaveBeenCalled();
    owner.sync.close();
    mirror.sync.close();
  });
});
