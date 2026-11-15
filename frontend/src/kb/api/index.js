import http from './http'

/*
 * 知识库页面沿用 console 的调用签名与数据形态；
 * 本层负责映射薪酬后端的参数名与响应结构，页面组件保持 console 原样。
 */

const STATUS_SUMMARY_KEYS = [
  'total',
  'pending',
  'parsing',
  'chunking',
  'indexing',
  'ready',
  'failed',
  'disabled'
]

function mapSeries(raw) {
  if (!raw) return raw
  return {
    ...raw,
    configVersionId: raw.configVersionId ?? raw.activeConfigVersionId ?? null
  }
}

function mapDirectory(node) {
  return {
    ...node,
    docCount: node.docCount ?? node.documentCount ?? 0,
    children: (node.children || []).map(mapDirectory)
  }
}

function mapTag(tag) {
  return { ...tag, docCount: tag.docCount ?? tag.documentCount ?? 0 }
}

function mapDocument(doc) {
  return {
    ...doc,
    directoryName: doc.directoryName ?? doc.directoryPath ?? '',
    tags: (doc.tags || []).map(mapTag)
  }
}

function mapTask(task) {
  return { ...task, configVersionId: task.configVersionId ?? null }
}

function mapConfig(raw) {
  if (!raw) return raw
  return {
    ...raw,
    parse: raw.parse ?? raw.parseConfig,
    chunk: raw.chunk ?? raw.chunkConfig,
    retrieval: raw.retrieval ?? raw.retrievalConfig
  }
}

function normalizeExtra(extra) {
  return String(extra || '').replace(/ · null$/, '')
}

function mapMember(member) {
  const [account, deptName] = normalizeExtra(member.subjectExtra).split(' · ')
  return {
    ...member,
    account: member.account ?? account ?? '',
    deptName: member.deptName ?? deptName ?? '',
    source: member.source ?? (member.subjectType === 'DEPT' ? '部门授权' : '个人授权'),
    grantedAt: member.grantedAt ?? member.createdAt,
    memberCount: member.memberCount ?? 0
  }
}

function mapSummary(raw) {
  const summary = {}
  for (const key of STATUS_SUMMARY_KEYS) {
    summary[key.toUpperCase()] = Number(raw?.[key] ?? 0)
  }
  return summary
}

function mapFailedItems(messages) {
  return (messages || []).map((message) => {
    const text = String(message)
    const index = text.indexOf('：')
    return index > 0
      ? { name: text.slice(0, index), message: text.slice(index + 1) }
      : { name: '', message: text }
  })
}

function paginate(list, page, size) {
  if (!size) return { items: list, total: list.length }
  const safePage = Math.max(1, Number(page) || 1)
  const from = (safePage - 1) * size
  return { items: list.slice(from, from + size), total: list.length }
}

export const authApi = {
  login: (data) => http.post('/auth/login', data, { silent: true }),
  me: () => http.get('/auth/me', { silent: true }),
  logout: () => http.post('/auth/logout', {}, { silent: true })
}

export const orgApi = {
  // 薪酬没有部门树接口，扁平部门列表即可满足选择器
  departments: () => http.get('/admin/departments'),
  users: () =>
    http.get('/admin/users').then((list) =>
      (list || []).map((user) => ({
        id: user.id,
        name: user.displayName || user.username,
        account: user.username,
        deptName: user.departmentName || ''
      }))
    )
}

export const seriesApi = {
  list: async (params = {}) => {
    const { deptId, page = 1, size = 12, scope, ...rest } = params
    const query = { ...rest, page, size, dept_id: deptId }
    if (scope === 'maintainable') {
      // 薪酬只识别 mine 维度，可维护范围按库级角色在客户端过滤
      const data = await http.get('/kb/series', { params: { ...query, page: 1, size: 100 } })
      const maintainable = (data.items || [])
        .map(mapSeries)
        .filter((item) => ['EDITOR', 'ADMIN', 'OWNER'].includes(item.role))
      return paginate(maintainable, page, size)
    }
    if (scope === 'mine') query.scope = 'mine'
    const data = await http.get('/kb/series', { params: query })
    return { ...data, items: (data.items || []).map(mapSeries) }
  },
  create: (data) => http.post('/kb/series', data).then(mapSeries),
  detail: (id) => http.get(`/kb/series/${id}`).then(mapSeries),
  update: (id, data) => http.patch(`/kb/series/${id}`, data).then(mapSeries),
  disable: (id) => http.post(`/kb/series/${id}/disable`).then(mapSeries),
  enable: (id) => http.post(`/kb/series/${id}/enable`).then(mapSeries),
  remove: (id) => http.delete(`/kb/series/${id}`),
  stats: (id) =>
    http.get(`/kb/series/${id}/stats`).then((raw) => ({
      ...raw,
      docCount: raw.documentCount ?? raw.docCount ?? 0,
      readyDocCount: raw.readyCount ?? raw.readyDocCount ?? 0,
      failedDocCount: raw.failedCount ?? raw.failedDocCount ?? 0
    }))
}

export const configApi = {
  current: (seriesId) => http.get(`/kb/series/${seriesId}/config`).then(mapConfig),
  versions: (seriesId) =>
    http.get(`/kb/series/${seriesId}/config-versions`).then((list) => (list || []).map(mapConfig)),
  version: (seriesId, versionId) =>
    http.get(`/kb/series/${seriesId}/config-versions/${versionId}`).then(mapConfig),
  save: (seriesId, data) =>
    http
      .post(`/kb/series/${seriesId}/config-versions`, {
        indexStrategy: data.indexStrategy,
        parseConfig: data.parse,
        chunkConfig: data.chunk,
        retrievalConfig: data.retrieval,
        changeNote: data.changeNote,
        rebuildMode: data.rebuild?.scope
      })
      .then((result) => {
        const config = mapConfig(result?.config ?? result)
        return {
          ...result,
          configVersionId: config?.versionId,
          versionNo: config?.versionNo
        }
      })
}

export const memberApi = {
  list: (seriesId) =>
    http.get(`/kb/series/${seriesId}/members`).then((list) => (list || []).map(mapMember)),
  candidates: (seriesId) =>
    http
      .get(`/kb/series/${seriesId}/members/candidates`)
      .then((list) => (list || []).map((item) => ({ ...item, extra: normalizeExtra(item.extra) }))),
  grant: async (seriesId, grants) => {
    for (const grant of grants || []) {
      await http.post(`/kb/series/${seriesId}/members`, {
        subjectType: grant.subjectType,
        subjectIds: [grant.subjectId],
        role: grant.role
      })
    }
    return http.get(`/kb/series/${seriesId}/members`).then((list) => (list || []).map(mapMember))
  },
  updateRole: (seriesId, memberId, role) =>
    http.patch(`/kb/series/${seriesId}/members/${memberId}`, { role }),
  remove: (seriesId, memberId) => http.delete(`/kb/series/${seriesId}/members/${memberId}`)
}

export const directoryApi = {
  tree: (seriesId) =>
    http.get(`/kb/series/${seriesId}/directories`).then((list) => (list || []).map(mapDirectory)),
  create: (seriesId, data) => http.post(`/kb/series/${seriesId}/directories`, data),
  rename: (seriesId, id, name) => http.patch(`/kb/series/${seriesId}/directories/${id}`, { name }),
  move: (seriesId, id, targetParentId) =>
    http.post(`/kb/series/${seriesId}/directories/${id}/move`, { parentId: targetParentId }),
  // 薪酬以 DELETE body 确认非空目录删除，没有迁移到上级目录策略
  remove: (seriesId, id, options = {}) =>
    http.delete(`/kb/series/${seriesId}/directories/${id}`, {
      data: { confirmName: options.confirmName }
    })
}

export const tagApi = {
  list: (seriesId) =>
    http.get(`/kb/series/${seriesId}/tags`).then((list) => (list || []).map(mapTag)),
  create: (seriesId, data) => http.post(`/kb/series/${seriesId}/tags`, data).then(mapTag),
  update: (seriesId, id, data) => http.patch(`/kb/series/${seriesId}/tags/${id}`, data).then(mapTag),
  remove: (seriesId, id) => http.delete(`/kb/series/${seriesId}/tags/${id}`)
}

export const documentApi = {
  list: async (seriesId, params = {}) => {
    const { directoryId, page, size, ...rest } = params
    const list = await http.get(`/kb/series/${seriesId}/documents`, {
      params: { ...rest, directory_id: directoryId }
    })
    return paginate((list || []).map(mapDocument), page, size)
  },
  upload: (seriesId, formData) => {
    const body = new FormData()
    for (const [key, value] of formData.entries()) {
      if (key === 'directoryId') body.append('directory_id', value)
      else if (key === 'dedupeAction') body.append('dedupe_action', value)
      else body.append(key, value)
    }
    return http.post(`/kb/series/${seriesId}/documents`, body).then((result) => ({
      ...result,
      items: (result?.documents || []).map(mapDocument),
      failedItems: mapFailedItems(result?.messages)
    }))
  },
  statusSummary: (seriesId) =>
    http.get(`/kb/series/${seriesId}/documents/status-summary`).then(mapSummary),
  detail: (seriesId, id) => http.get(`/kb/series/${seriesId}/documents/${id}`).then(mapDocument),
  update: (seriesId, id, data) =>
    http.patch(`/kb/series/${seriesId}/documents/${id}`, data).then(mapDocument),
  remove: (seriesId, id) => http.delete(`/kb/series/${seriesId}/documents/${id}`),
  reparse: (seriesId, id) =>
    http.post(`/kb/series/${seriesId}/documents/${id}/reparse`).then(mapDocument),
  disable: (seriesId, id) =>
    http.post(`/kb/series/${seriesId}/documents/${id}/disable`).then(mapDocument),
  enable: (seriesId, id) =>
    http.post(`/kb/series/${seriesId}/documents/${id}/enable`).then(mapDocument),
  chunks: (seriesId, id, params) =>
    http.get(`/kb/series/${seriesId}/documents/${id}/chunks`, { params }),
  tags: (seriesId, id) =>
    http
      .get(`/kb/series/${seriesId}/documents/${id}`)
      .then((doc) => (doc?.tags || []).map(mapTag)),
  setTags: (seriesId, id, tagIds) =>
    http
      .put(`/kb/series/${seriesId}/documents/${id}/tags`, { tagIds })
      .then((list) => (list || []).map(mapTag)),
  batch: (seriesId, data) =>
    http.post(`/kb/series/${seriesId}/documents/batch`, data).then((result) => ({
      ...result,
      successCount: result?.succeeded ?? 0,
      failedCount: result?.failed ?? 0,
      failedItems: mapFailedItems(result?.messages)
    }))
}

export const taskApi = {
  // 薪酬任务接口只支持 limit，取足后在前端切片
  list: async (seriesId, params = {}) => {
    const { page = 1, size = 20, ...rest } = params
    const list = await http.get(`/kb/series/${seriesId}/tasks`, {
      params: { ...rest, limit: Math.min(200, Math.max(size, page * size)) }
    })
    return paginate((list || []).map(mapTask), page, size)
  },
  detail: (seriesId, id) =>
    http.get(`/kb/series/${seriesId}/tasks/${id}`).then((detail) => ({
      ...detail,
      task: mapTask(detail?.task)
    })),
  logs: (seriesId, id) =>
    http.get(`/kb/series/${seriesId}/tasks/${id}`).then((detail) => detail?.logs || []),
  retry: (seriesId, id) => http.post(`/kb/series/${seriesId}/tasks/${id}/retry`).then(mapTask),
  cancel: (seriesId, id) => http.post(`/kb/series/${seriesId}/tasks/${id}/cancel`).then(mapTask)
}

export const retrievalApi = {
  test: (seriesId, data) =>
    http
      .post(`/kb/series/${seriesId}/retrieval/test`, {
        query: data.query,
        topK: data.topK,
        threshold: data.threshold,
        withAnswer: false
      })
      .then((result) => {
        const hits = result?.hits || []
        const diagnostics = result?.diagnostics || {}
        return {
          query: result?.query,
          answer: result?.answer || '',
          noAnswer: hits.length === 0,
          totalCandidates: diagnostics.fused ?? diagnostics.candidates ?? hits.length,
          returned: hits.length,
          tookMs: result?.tookMs ?? 0,
          indexStrategy: result?.strategy,
          hybrid: diagnostics.hybrid ?? result?.strategy !== 'VECTOR',
          rerank: Boolean(diagnostics.rerank),
          retrievalEngine: diagnostics.engine === 'ELASTICSEARCH' ? 'ELASTICSEARCH' : 'LOCAL',
          citations: hits.map((hit) => ({
            ...hit,
            seq: hit.chunkSeq ?? hit.seq,
            chunkLevel: hit.parentContent ? 'CHILD' : 'SINGLE',
            documentExt: String(hit.documentName || '').includes('.')
              ? String(hit.documentName).split('.').pop().toUpperCase()
              : ''
          }))
        }
      })
}

