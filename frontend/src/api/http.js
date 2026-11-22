/** 统一请求封装：ApiResponse{status,data,message}，401 跳回登录页。 */
export class ApiError extends Error {
  constructor(message, status, data = null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.data = data;
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
      response.status,
      (payload && payload.data) || null
    );
  }
  return payload ? payload.data : null;
}

/** 导入接口接收原始文件字节（@RequestBody byte[]），不是 multipart。 */
export async function upload(path, file) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': file.type || 'application/octet-stream' },
    body: file,
    credentials: 'same-origin',
  });
  return unwrap(response, '导入失败');
}

/** multipart 上传：对话附件接口接收 files 多文件字段。 */
export async function uploadForm(path, formData) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { Accept: 'application/json' },
    body: formData,
    credentials: 'same-origin',
  });
  return unwrap(response, '上传失败');
}

async function unwrap(response, fallback) {
  if (response.status === 401) {
    window.location.href = '/login';
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
      (payload && payload.message) || `${fallback}：HTTP ${response.status}`,
      response.status,
      (payload && payload.data) || null
    );
  }
  return payload ? payload.data : null;
}
