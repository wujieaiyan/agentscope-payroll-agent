/**
 * 对话事件流：优先 POST /stream，断线后用轮次事件补偿续流。
 * 事件字段与后端 ChatSse 保持一致（snake_case）。
 */
export async function streamChat(sessionId, message, onEvent, signal) {
  const response = await fetch(`/api/chat/sessions/${sessionId}/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message }),
    signal,
  });
  await consume(response, onEvent);
}

export async function resumeTurn(sessionId, turnId, afterSeq, onEvent, signal) {
  const response = await fetch(
    `/api/chat/sessions/${sessionId}/turns/${encodeURIComponent(turnId)}/events?after_seq=${afterSeq}`,
    { headers: { Accept: 'text/event-stream' }, signal }
  );
  await consume(response, onEvent);
}

async function consume(response, onEvent) {
  if (response.status === 401) {
    window.location.href = '/login';
    throw new Error('未登录或会话已过期');
  }
  if (!response.ok) {
    let payload = null;
    try {
      payload = JSON.parse(await response.text());
    } catch (error) {
      payload = null;
    }
    throw new Error((payload && payload.message) || `请求失败：HTTP ${response.status}`);
  }
  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  const dispatch = (raw) => {
    let event = 'message';
    let eventId = 0;
    const dataLines = [];
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('id:')) {
        eventId = Math.max(0, Number(line.slice(3).trim()) || 0);
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart());
      }
    }
    if (!dataLines.length) return;
    try {
      const data = JSON.parse(dataLines.join('\n'));
      // SSE id 是续传游标的兜底来源：负载里没有 seq 时补上
      if (eventId > 0 && data && typeof data === 'object' && !Number.isFinite(data.seq)) {
        data.seq = eventId;
      }
      onEvent(event, data);
    } catch (error) {
      if (error instanceof SyntaxError) return;
      throw error;
    }
  };
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
      let index;
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, index);
        buffer = buffer.slice(index + 2);
        if (raw.trim()) dispatch(raw);
      }
    }
    buffer = (buffer + decoder.decode()).replace(/\r\n/g, '\n').trim();
    if (buffer) dispatch(buffer);
  } finally {
    reader.cancel().catch(() => {});
  }
}
