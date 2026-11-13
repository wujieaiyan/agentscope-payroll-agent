/** 统一请求封装：ApiResponse{status,data,message}，401 跳回登录页。 */
export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

export async function api(path, options = {}) {
  const init = {
    method: options.method || 'GET',
    headers: { Accept: 'application/json', ...(options.headers || {}) },
    credentials: 'same-origin',
  };
  if (options.body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(options.body);
  }
  const response = await fetch(path, init);
  if (response.status === 401) {
    if (!path.startsWith('/api/auth/')) {
      window.location.href = '/login';
    }
    throw new ApiError('未登录或会话已过期', 401);
  }
  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch (error) {
      payload = { status: 'ERROR', message: text };
    }
  }
  if (!response.ok || (payload && payload.status === 'ERROR')) {
    throw new ApiError(
      (payload && payload.message) || `请求失败：HTTP ${response.status}`,
      response.status
    );
  }
  return payload ? payload.data : null;
}

export async function upload(path, file, fields = {}) {
  const form = new FormData();
  form.append('file', file);
  Object.entries(fields).forEach(([key, value]) => form.append(key, value));
  const response = await fetch(path, { method: 'POST', body: form, credentials: 'same-origin' });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok || (payload && payload.status === 'ERROR')) {
    throw new ApiError((payload && payload.message) || `上传失败：HTTP ${response.status}`, response.status);
  }
  return payload ? payload.data : null;
}
