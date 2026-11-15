export const seriesStatusMap = {
  NORMAL: { label: '正常', type: 'success' },
  PARTIAL: { label: '部分异常', type: 'warning' },
  INITIALIZING: { label: '初始化中', type: 'info' },
  DRAFT: { label: '草稿', type: 'info' },
  ACTIVE: { label: '正常', type: 'success' },
  DISABLED: { label: '已停用', type: 'info' },
  DELETING: { label: '删除中', type: 'danger' },
  DELETED: { label: '已删除', type: 'info' }
}

export const docStatusMap = {
  PENDING: { label: '排队中', type: 'info' },
  PARSING: { label: '解析中', type: 'warning' },
  CHUNKING: { label: '切片中', type: 'warning' },
  INDEXING: { label: '索引中', type: 'warning' },
  READY: { label: '已就绪', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  DISABLED: { label: '已停用', type: 'info' },
  DELETING: { label: '删除中', type: 'danger' },
  DELETED: { label: '已删除', type: 'info' }
}

export const taskStatusMap = {
  QUEUED: { label: '排队中', type: 'info' },
  RUNNING: { label: '执行中', type: 'warning' },
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  CANCELING: { label: '取消中', type: 'warning' },
  CANCELED: { label: '已取消', type: 'info' }
}

export const taskTypeMap = {
  INGEST: '文档入库',
  REBUILD: '失败重建',
  FULL_REBUILD: '全量重建',
  DELETE_CLEANUP: '删除清理',
  REPARSE: '重新解析'
}

export const roleMap = {
  OWNER: { label: '拥有者', type: 'danger' },
  ADMIN: { label: '管理员', type: 'warning' },
  EDITOR: { label: '编辑者', type: 'primary' },
  READER: { label: '只读', type: 'info' }
}

export const indexStrategyMap = {
  VECTOR: '向量检索',
  HYBRID: '混合检索',
  GRAPH: '图谱增强'
}

export const chunkStrategyMap = {
  DEFAULT: '默认切分',
  BY_LINE: '按行切分',
  PARENT_CHILD: '父子切分',
  TITLE_ENHANCED: '标题增强',
  CUSTOM: '自定义切分'
}

export const parseStrategyMap = {
  NATIVE: '文字提取',
  OCR: 'OCR 识别',
  AUTO: '自动判断'
}

export const statusOf = (map, value) => map[value] || { label: value || '-', type: 'info' }

export const canMaintain = (role) => ['EDITOR', 'ADMIN', 'OWNER'].includes(role)
export const canAdmin = (role) => ['ADMIN', 'OWNER'].includes(role)
export const canEdit = canMaintain
