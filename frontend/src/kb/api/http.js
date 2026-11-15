import { ElMessage } from 'element-plus'

const BASE = '/api'

async function request(method, path, options = {}) {
  let url = BASE + path
  const init = {
    method,
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...(options.headers || {}) },
  }
  if (options.params) {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(options.params)) {
      if (value !== undefined && value !== null && value !== '') {
        query.append(key, value)
      }
    }
    const text = query.toString()
    if (text) {
      url += (url.includes('?') ? '&' : '?') + text
    }
  }
  const body = options.data
  if (body instanceof FormData) {
    delete init.headers['Content-Type']
    init.body = body
  } else if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }

  const response = await fetch(url, init)
  if (response.status === 401) {
    if (!window.location.pathname.startsWith('/login')) {
      window.location.href = '/login'
    }
    throw { status: 401, message: '未登录或会话已过期' }
  }
  const text = await response.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch (error) {
      payload = null
    }
  }
  if (!response.ok || (payload && payload.status === 'ERROR')) {
    const message = (payload && payload.message) || `请求失败（${response.status}）`
    if (!options.silent) {
      ElMessage.error(message)
    }
    throw { ...(payload || {}), status: response.status, message }
  }
  return payload ? payload.data : null
}

const http = {
  get: (path, options) => request('GET', path, options),
  post: (path, data, options) => request('POST', path, { ...(options || {}), data }),
  patch: (path, data, options) => request('PATCH', path, { ...(options || {}), data }),
  put: (path, data, options) => request('PUT', path, { ...(options || {}), data }),
  delete: (path, options) => request('DELETE', path, options),
}

export default http
