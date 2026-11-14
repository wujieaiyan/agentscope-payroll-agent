/**
 * 多标签页协调：同一会话只让一个标签页读取服务端事件流，其余标签页镜像渲染。
 *
 * <p>协议基于 BroadcastChannel，均为同源同浏览器内的轻量消息：
 * probe/owner 探测当前流主人，sync/state 拉取中途快照，event/end 转发与收尾。
 * 浏览器不支持 BroadcastChannel 时整体退化为各标签页独立拉流。
 */
const CHANNEL_NAME = 'payroll.chat.tabs.v1';
const PROBE_TIMEOUT_MS = 300;
const STATE_TIMEOUT_MS = 300;

const delay = (ms) => new Promise((resolve) => window.setTimeout(resolve, ms));

export function createTabSync({ snapshot, onEvent, onState, onEnd }) {
  const supported =
    typeof window !== 'undefined' && typeof window.BroadcastChannel === 'function';
  const channel = supported ? new BroadcastChannel(CHANNEL_NAME) : null;
  const tabId = `tab-${Math.random().toString(36).slice(2)}-${Date.now().toString(36)}`;
  const owned = new Map(); // sessionId -> turnId，本标签页正在拉流的会话
  const owners = new Map(); // sessionId -> { turnId, lastSeq, tabId }，其它标签页的流主人
  const ownerWaiters = new Map(); // sessionId -> resolve(owner|null)
  const stateWaiters = new Map(); // sessionId -> resolve(snapshot|null)

  function post(type, payload) {
    if (!channel) return;
    try {
      channel.postMessage({ type, ...payload, from: tabId });
    } catch (error) {
      // 通道关闭或负载不可序列化时放弃协调，不影响本页拉流
    }
  }

  function settle(map, sessionId, value) {
    const resolve = map.get(sessionId);
    if (resolve) {
      map.delete(sessionId);
      resolve(value);
    }
  }

  if (channel) {
    channel.addEventListener('message', (event) => {
      const data = event.data;
      if (!data || data.from === tabId) return;
      switch (data.type) {
        case 'probe': {
          if (!owned.has(data.sessionId)) return;
          const current = snapshot();
          if (!current) return;
          post('owner', {
            sessionId: data.sessionId,
            turnId: owned.get(data.sessionId),
            lastSeq: current.lastSeq,
          });
          break;
        }
        case 'owner': {
          owners.set(data.sessionId, {
            turnId: data.turnId || null,
            lastSeq: Math.max(0, Number(data.lastSeq) || 0),
            tabId: data.from,
          });
          settle(ownerWaiters, data.sessionId, data.from);
          break;
        }
        case 'claim': {
          owners.set(data.sessionId, {
            turnId: data.turnId || null,
            lastSeq: 0,
            tabId: data.from,
          });
          break;
        }
        case 'release': {
          const current = owners.get(data.sessionId);
          if (current && current.tabId === data.from) owners.delete(data.sessionId);
          break;
        }
        case 'sync': {
          if (!owned.has(data.sessionId)) return;
          const current = snapshot();
          if (!current) return;
          post('state', { sessionId: data.sessionId, snapshot: current });
          break;
        }
        case 'state': {
          settle(stateWaiters, data.sessionId, data.snapshot || null);
          break;
        }
        case 'event': {
          const current = owners.get(data.sessionId);
          if (current) {
            current.lastSeq = Math.max(current.lastSeq, Number(data.data?.seq) || 0);
          }
          onEvent(data.sessionId, data.turnId, data.event, data.data);
          break;
        }
        case 'end': {
          const current = owners.get(data.sessionId);
          if (current && current.tabId === data.from) owners.delete(data.sessionId);
          onEnd(data.sessionId, data.turnId, data.flags || {});
          break;
        }
        default:
          break;
      }
    });
  }

  return {
    available() {
      return !!channel;
    },

    /** 本页开始拉流：登记所有权，后续探测可直接应答。 */
    claim(sessionId, turnId) {
      owned.set(sessionId, turnId || null);
      post('claim', { sessionId, turnId: turnId || null });
    },

    /** 探测其它标签页是否已在拉同一会话；是则拉取快照并转为镜像。 */
    async follow(sessionId) {
      if (!channel || owned.has(sessionId)) return false;
      owners.delete(sessionId);
      const waiting = new Promise((resolve) => ownerWaiters.set(sessionId, resolve));
      post('probe', { sessionId });
      const reply = await Promise.race([waiting, delay(PROBE_TIMEOUT_MS).then(() => null)]);
      ownerWaiters.delete(sessionId);
      if (!reply) return false;

      const target = owners.get(sessionId);
      const stateWaiting = new Promise((resolve) => stateWaiters.set(sessionId, resolve));
      post('sync', { sessionId, turnId: target ? target.turnId : null });
      const current = await Promise.race([stateWaiting, delay(STATE_TIMEOUT_MS).then(() => null)]);
      stateWaiters.delete(sessionId);
      onState(sessionId, target ? target.turnId : null, current);
      return true;
    },

    /** 主人转发一条事件给其它标签页。 */
    event(sessionId, turnId, event, data) {
      if (!owned.has(sessionId)) return;
      post('event', { sessionId, turnId: turnId || null, event, data });
    },

    /** 结束分享：正常完成时带终态，关闭页面时标记 handoff 交由他人接管。 */
    release(sessionId, turnId, flags = {}) {
      if (!owned.has(sessionId)) return;
      owned.delete(sessionId);
      post('end', { sessionId, turnId: turnId || null, flags });
    },

    close() {
      for (const [sessionId, turnId] of owned.entries()) {
        post('end', { sessionId, turnId, flags: { handoff: true } });
      }
      owned.clear();
      ownerWaiters.forEach((resolve) => resolve(null));
      ownerWaiters.clear();
      stateWaiters.forEach((resolve) => resolve(null));
      stateWaiters.clear();
      if (channel) channel.close();
    },
  };
}
