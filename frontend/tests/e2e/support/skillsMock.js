/**
 * 技能管理台的统一 API mock：一个 catch-all 路由内按路径分派。
 * 功能冒烟与视觉基线共用，避免两套 mock 漂移；所有写请求都记入 calls 供断言。
 */

const T = (iso) => Date.parse(iso);
const UPDATED_AT = T('2026-11-24T10:00:00');
const CREATED_AT = T('2026-11-20T09:00:00');
const DECLARED_MAX = 2 * 1024 * 1024 * 1024;

const FILLER =
  '回答时先复述问题要点，再核对政策原文，最后给出结论与依据；遇到口径冲突时列出差异并请用户确认。\n';

export const SKILL_BODY = `## 角色
你是薪酬政策助手，负责回答员工关于工资、社保与考勤口径的问题。
## 规则
- 先核对政策原文，再给出结论。
- 不确定时明确说出缺少的依据，不要猜测。
- 涉及金额时给出计算公式与取数来源。
## 输出要求
用三句话以内回答，并附上依据来源。
${FILLER.repeat(3)}`;

const SHORT_BODY = '## 角色\n太短';

const PERMISSIONS = [
  'agent:manage',
  'flow:manage',
  'skill:manage',
  'kb:read',
  'employee:read',
  'review:read',
  'attendance:read',
  'payroll:read',
  'payroll:approve',
  'policy:read',
  'user:manage',
  'audit:read',
  'usage:read',
  'profile:read',
];

const ME = {
  user: {
    id: 'u-1',
    username: 'admin',
    display_name: '管理员',
    avatar: '管',
    permissions: PERMISSIONS,
  },
  auth_enabled: true,
  profile_enabled: true,
  must_change_password: false,
};

const USERS = { admin: '管理员' };

const POETRY_BINDINGS = [
  { agentId: 'poetry-agent', name: '诗词助手', description: '按主题创作格律诗' },
];

const TEMPLATES = [
  {
    id: 'policy-qa',
    title: '薪酬政策问答',
    description: '按政策原文回答口径类问题',
    category: '政策口径',
    body: SKILL_BODY,
    bodyLength: SKILL_BODY.length,
    usedCount: 3,
    lastUsedAt: T('2026-11-22T10:00:00'),
  },
  {
    id: 'process-guide',
    title: '流程操作指引',
    description: '把操作步骤写成可执行的检查清单',
    category: '处理流程',
    body: SKILL_BODY,
    bodyLength: SKILL_BODY.length,
    usedCount: 1,
    lastUsedAt: T('2026-11-21T15:00:00'),
  },
  {
    id: 'writing-standard',
    title: '写作规范',
    description: '统一通知与说明的写作口径',
    category: '写作规范',
    body: SKILL_BODY,
    bodyLength: SKILL_BODY.length,
    usedCount: 0,
  },
  {
    id: 'data-analysis',
    title: '数据分析',
    description: '按指标口径做汇总与解释',
    category: '数据分析',
    body: SKILL_BODY,
    bodyLength: SKILL_BODY.length,
    usedCount: 0,
  },
];

const FILE_CONTENT = {
  'references/rules.md': '# 规则\n先核对政策原文。',
  'scripts/setup.sh': '#!/bin/sh\npip install -r requirements.txt\n',
};

const ok = (data) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ status: 'OK', data }),
});
const fail = (status, message, data) => ({
  status,
  contentType: 'application/json',
  body: JSON.stringify({ status: 'ERROR', message, data }),
});

function formField(raw, name) {
  const match = raw.match(new RegExp(`name="${name}"\\r?\\n\\r?\\n([^\\r\\n]*)`));
  return match ? match[1] : '';
}

export function createSkillsMock(options = {}) {
  const state = {
    source: 'db',
    writable: true,
    versionNo: 7,
    conflict: false,
    importExisting: true,
    sandboxEnabled: true,
    ...options,
  };
  state.revision = options.revision || `rev-${state.versionNo}`;

  const calls = {
    created: [],
    updated: [],
    deleted: [],
    importedGit: [],
    importedZip: [],
    previewGit: [],
    previewZip: [],
    updatedFile: [],
    dataInit: [],
    dataTree: [],
    dataFile: [],
    rollbacks: [],
  };
  const createdSkills = new Map();

  async function reply(route, payload) {
    await route.fulfill(payload);
    return true;
  }

  function fileList() {
    if (state.source !== 'db') return [];
    return [
      { path: 'references/rules.md', size: 120, kind: 'reference' },
      { path: 'scripts/setup.sh', size: 60, kind: 'script' },
    ];
  }

  function detailOf(id) {
    if (createdSkills.has(id)) return { ...createdSkills.get(id) };
    const policy = id === 'payroll-policy-notes';
    return {
      id,
      description: policy ? '薪酬政策问答' : '诗词创作技能',
      content: SKILL_BODY,
      source: state.source,
      builtin: false,
      overridden: false,
      createdAt: policy ? T('2026-11-19T09:00:00') : CREATED_AT,
      updatedAt: state.source === 'db' ? UPDATED_AT : 0,
      files: policy ? [{ path: 'references/policy.md', size: 88, kind: 'reference' }] : fileList(),
      executionEnabled: !policy,
      metadata: {},
      revision: state.source === 'db' ? (policy ? 'rev-3' : state.revision) : '',
      versionNo: state.source === 'db' ? (policy ? 3 : state.versionNo) : 0,
      createdBy: 'admin',
      updatedBy: 'admin',
      needsData: state.source === 'db' ? true : null,
    };
  }

  function summaryOf(id) {
    const detail = detailOf(id);
    return {
      id: detail.id,
      description: detail.description,
      source: detail.source,
      builtin: false,
      overridden: false,
      updatedAt: detail.updatedAt,
      contentLength: detail.content.length,
      fileCount: detail.files.length,
      revision: detail.revision,
      versionNo: detail.versionNo,
      updatedBy: detail.updatedBy,
      needsData: detail.needsData,
      dataMaxBytes: detail.needsData ? DECLARED_MAX : null,
    };
  }

  const SKILL_IDS = ['poetry-skills', 'payroll-policy-notes'];

  function bindingsMap() {
    return { 'poetry-skills': state.source === 'db' ? POETRY_BINDINGS : [] };
  }

  function dataStatesMap() {
    if (state.source !== 'db') return {};
    return {
      'poetry-skills': { status: 'READY', message: '' },
      'payroll-policy-notes': { status: 'FAILED', message: '初始化失败：依赖下载超时' },
    };
  }

  function dataBucket(summary) {
    if (summary.needsData === false) return 'none';
    if (summary.needsData === null || summary.needsData === undefined) return 'unknown';
    const status = dataStatesMap()[summary.id]?.status;
    if (status === 'READY') return 'ready';
    if (['FAILED', 'UNAVAILABLE', 'INVALID'].includes(status)) return 'attention';
    if (['INITIALIZING', 'BUSY'].includes(status)) return 'initializing';
    return 'uninitialized';
  }

  function statsOf() {
    const db = state.source === 'db';
    const ready = db ? 1 : 0;
    const attention = db ? 1 : 0;
    const bound = db ? 1 : 0;
    return {
      totalSkills: SKILL_IDS.length,
      usable: ready,
      needsAttention: attention,
      initializing: 0,
      unknownData: db ? 0 : SKILL_IDS.length,
      unbound: SKILL_IDS.length - bound,
      boundAgents: bound,
      boundSkills: bound,
      contentBytes: SKILL_BODY.length * 2,
      catalogBuildMillis: 12,
      cacheHitRatio: 0.98,
      views: {
        all: SKILL_IDS.length,
        custom: SKILL_IDS.length,
        builtin: 0,
        overridden: 0,
        bound,
        attention,
        unbound: SKILL_IDS.length - bound,
      },
    };
  }

  function listPayload(url) {
    const db = state.source === 'db';
    const params = url?.searchParams || new URLSearchParams();
    const keyword = String(params.get('keyword') || '').trim().toLowerCase();
    const view = params.get('view') || 'all';
    const dataStatus = params.get('dataStatus') || 'all';
    const sort = params.get('sort') || 'updated';
    const bindings = bindingsMap();
    let skills = SKILL_IDS.map((id) => summaryOf(id));
    if (keyword) {
      skills = skills.filter(
        (skill) =>
          skill.id.toLowerCase().includes(keyword) ||
          String(skill.description || '').toLowerCase().includes(keyword)
      );
    }
    if (view === 'builtin' || view === 'overridden') skills = [];
    if (view === 'bound') skills = skills.filter((skill) => bindings[skill.id]?.length);
    if (view === 'unbound') skills = skills.filter((skill) => !bindings[skill.id]?.length);
    if (view === 'attention') {
      skills = skills.filter((skill) => dataBucket(skill) === 'attention');
    }
    if (dataStatus !== 'all') {
      skills = skills.filter((skill) => dataBucket(skill) === dataStatus);
    }
    if (sort === 'id') skills.sort((a, b) => a.id.localeCompare(b.id));
    if (sort === 'size') skills.sort((a, b) => b.contentLength - a.contentLength);
    return {
      writable: db && state.writable,
      location: db ? 'db 源' : 'Nacos 源',
      sandboxEnabled: state.sandboxEnabled,
      skills,
      total: skills.length,
      page: 1,
      size: 20,
      stats: statsOf(),
      bindings,
      dataStates: dataStatesMap(),
      users: USERS,
    };
  }

  function checklistOf(id, detail) {
    const db = state.source === 'db';
    const policy = id === 'payroll-policy-notes';
    const needsData = db && detail.needsData !== false;
    return [
      { key: 'body', label: '正文通过质量检查', ok: true, detail: `v${detail.versionNo}`, action: 'body' },
      { key: 'files', label: '已添加参考文件', ok: detail.files.length > 0, detail: `${detail.files.length} 个`, action: 'files' },
      {
        key: 'data',
        label: '数据已就绪',
        ok: needsData && !policy,
        detail: !needsData ? '无需数据' : policy ? 'FAILED' : '1.0 MB / 2.0 GB',
        action: 'data',
      },
      {
        key: 'binding',
        label: '已绑定至少 1 个智能体',
        ok: db && id === 'poetry-skills',
        detail: db && id === 'poetry-skills' ? '1 个' : '0 个',
        action: 'bindings',
      },
    ];
  }

  function overviewOf(id) {
    const detail = detailOf(id);
    const db = state.source === 'db';
    const bound = db && id === 'poetry-skills';
    const policy = id === 'payroll-policy-notes';
    const needsData = db && detail.needsData !== false;
    const scriptCount = (detail.files || []).filter((file) => file.kind === 'script').length;
    const versionNo = detail.versionNo;
    const timeline = [];
    if (db) {
      timeline.push({
        versionNo,
        changeNote: '补规则',
        createdBy: 'admin',
        createdAt: UPDATED_AT,
        current: true,
      });
      if (versionNo > 1) {
        timeline.push({
          versionNo: versionNo - 1,
          changeNote: '初稿',
          createdBy: 'admin',
          createdAt: T('2026-11-23T09:00:00'),
          current: false,
        });
      }
    }
    return {
      id,
      sandboxEnabled: state.sandboxEnabled,
      identity: {
        source: detail.source,
        writable: db && state.writable,
        versionNo,
        contentHash: 'a1b2c3d4e5f60718293a4b5c6d7e8f90',
        createdBy: detail.createdBy,
        createdAt: detail.createdAt,
        updatedBy: detail.updatedBy,
        updatedAt: detail.updatedAt,
      },
      health: {
        data: db
          ? {
              needsData,
              status: policy ? 'FAILED' : 'READY',
              sizeBytes: policy ? 0 : 1048576,
              declaredMaxBytes: DECLARED_MAX,
              startedAt: UPDATED_AT - 4200,
              finishedAt: UPDATED_AT - 1200,
              observedAt: UPDATED_AT,
              message: policy ? '初始化失败：依赖下载超时' : '',
            }
          : {
              needsData: null,
              status: 'UNKNOWN',
              sizeBytes: 0,
              declaredMaxBytes: null,
              startedAt: 0,
              finishedAt: 0,
              observedAt: 0,
              message: '',
            },
        scripts: {
          executionEnabled: detail.executionEnabled,
          count: scriptCount,
          setupCommand: scriptCount ? 'bash scripts/setup.sh' : null,
        },
        bindings: {
          count: bound ? POETRY_BINDINGS.length : 0,
          agents: bound ? POETRY_BINDINGS : [],
        },
      },
      checklist: checklistOf(id, detail),
      timeline,
      users: USERS,
    };
  }

  function versionsPayload(id) {
    const detail = detailOf(id);
    const versions = [];
    for (let versionNo = detail.versionNo; versionNo > 0 && versions.length < 5; versionNo -= 1) {
      versions.push({
        versionNo,
        changeNote: versionNo === detail.versionNo ? '补规则' : '初稿',
        contentLength: SKILL_BODY.length,
        createdBy: 'admin',
        createdAt: versionNo === detail.versionNo ? UPDATED_AT : T('2026-11-23T09:00:00'),
        current: versionNo === detail.versionNo,
      });
    }
    return { id, total: versions.length, versions };
  }

  const DATA_TREE = {
    '': [
      { name: 'poets', path: 'poets', type: 'dir', size: 0, modifiedAt: UPDATED_AT },
      { name: 'index.json', path: 'index.json', type: 'file', size: 20481, modifiedAt: UPDATED_AT, isText: true },
      { name: 'cover.png', path: 'cover.png', type: 'file', size: 512000, modifiedAt: UPDATED_AT, isText: false },
    ],
    poets: [
      { name: 'li-bai.md', path: 'poets/li-bai.md', type: 'file', size: 512, modifiedAt: UPDATED_AT, isText: true },
    ],
  };

  function dataTreePayload(id, path) {
    return {
      skillId: id,
      path,
      entries: state.dataEmpty ? [] : DATA_TREE[path] || [],
      truncated: false,
      entryLimit: 500,
    };
  }

  function dataFilePayload(path) {
    if (path.endsWith('.png')) {
      return {
        path,
        size: 512000,
        modifiedAt: UPDATED_AT,
        isText: false,
        truncated: false,
        mime: 'image/png',
      };
    }
    const content = path === 'poets/li-bai.md' ? '# 李白\n静夜思。\n' : '{\n  "poets": ["li-bai"]\n}\n';
    return {
      path,
      size: content.length,
      modifiedAt: UPDATED_AT,
      isText: true,
      encoding: 'utf-8',
      truncated: false,
      content,
      mime: path.endsWith('.md') ? 'text/markdown' : 'application/json',
    };
  }

  function dataPayload(id) {
    const failed = state.source === 'db' && id === 'payroll-policy-notes';
    return {
      status: failed ? 'FAILED' : 'READY',
      requiresData: true,
      sizeBytes: failed ? 0 : 1048576,
      declaredMaxBytes: DECLARED_MAX,
      startedAt: UPDATED_AT - 4200,
      finishedAt: UPDATED_AT - 1200,
      observedAt: UPDATED_AT,
      message: failed ? '初始化失败：依赖下载超时' : '',
      logTail: '',
    };
  }

  function importPreview(body, kind, fileName) {
    const id = body.id || 'poetry-skills';
    const existing = state.importExisting;
    const files = [
      { path: 'SKILL.md', size: 820, kind: 'reference', status: 'REPLACED' },
      { path: 'references/poetry.md', size: 400, kind: 'reference', status: 'ADDED' },
      { path: 'references/rules.md', size: 120, kind: 'reference', status: 'UNCHANGED' },
      { path: 'scripts/setup.sh', size: 60, kind: 'script', status: 'ADDED' },
    ];
    return {
      id,
      commitSha: 'abcdef1234567890',
      description: '诗词创作技能',
      metadata: {},
      source:
        kind === 'git'
          ? { kind: 'git', ref: body.ref || 'main', resolvedSha: 'abcdef1234567890' }
          : { kind: 'zip', ref: null, resolvedSha: 'abcdef1234567890' },
      elapsedMillis: 128,
      files,
      totals: { files: 4, bytes: 1400, added: 2, replaced: 1, unchanged: 1 },
      setupCommand: 'bash scripts/setup.sh',
      dataMaxBytes: 1073741824,
      warnings: [],
      existing,
      currentVersionNo: existing ? state.versionNo : 0,
      dryRun: Boolean(body.dryRun),
      fileName: kind === 'zip' ? fileName : undefined,
    };
  }

  function commitImport(body) {
    state.versionNo += 1;
    state.revision = `rev-${state.versionNo}`;
    return {
      id: body.id || 'poetry-skills',
      commitSha: 'abcdef1234567890',
      currentVersionNo: state.versionNo,
      dryRun: false,
    };
  }

  function jsonBody(request) {
    try {
      return JSON.parse(request.postData() || '{}');
    } catch (caught) {
      return {};
    }
  }

  async function handle(route) {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    if (!path.startsWith('/api/')) return false;
    if (path === '/api/auth/me') return reply(route, ok(ME));
    if (path === '/api/admin/agents') return reply(route, ok({ agents: [] }));

    if (path === '/api/admin/skills' && method === 'GET') {
      return reply(route, ok(listPayload(url)));
    }
    if (path === '/api/admin/skills' && method === 'POST') {
      const input = jsonBody(request);
      const detail = {
        id: input.id,
        description: input.description || '',
        content: input.content || '',
        source: 'db',
        builtin: false,
        overridden: false,
        createdAt: Date.now(),
        updatedAt: Date.now(),
        files: [],
        executionEnabled: Boolean(input.executionEnabled),
        metadata: {},
        revision: 'rev-1',
        versionNo: 1,
        createdBy: 'admin',
        updatedBy: 'admin',
        needsData: false,
      };
      createdSkills.set(detail.id, detail);
      calls.created.push(input);
      return reply(route, ok(detail));
    }
    if (path === '/api/admin/skills/templates') {
      return reply(route, ok({ templates: TEMPLATES, recent: TEMPLATES.slice(0, 2) }));
    }

    const importMatch = path.match(/^\/api\/admin\/skills\/import\/(git|zip)$/);
    if (importMatch && method === 'POST') {
      const kind = importMatch[1];
      const revision = url.searchParams.get('revision') || '';
      const raw = request.postData() || '';
      const body =
        kind === 'git'
          ? jsonBody(request)
          : {
              id: formField(raw, 'id') || null,
              overwrite: formField(raw, 'overwrite') === 'true',
              dryRun: formField(raw, 'dryRun') === 'true',
              revision: formField(raw, 'revision') || '',
            };
      if (kind === 'git' && body.dryRun) {
        calls.previewGit.push({ body, revision });
        return reply(route, ok(importPreview(body, kind)));
      }
      if (kind === 'zip' && body.dryRun) {
        calls.previewZip.push({ body, revision });
        return reply(route, ok(importPreview(body, kind, 'poetry.zip')));
      }
      const record = { body, revision: kind === 'zip' ? body.revision : revision };
      if (kind === 'git') calls.importedGit.push(record);
      else calls.importedZip.push(record);
      return reply(route, ok(commitImport(body)));
    }

    const filesMatch = path.match(/^\/api\/admin\/skills\/([^/]+)\/files\/(.+)$/);
    if (filesMatch) {
      const skillId = decodeURIComponent(filesMatch[1]);
      const filePath = decodeURIComponent(filesMatch[2]);
      if (method === 'PUT') {
        const body = jsonBody(request);
        calls.updatedFile.push({
          skillId,
          path: filePath,
          revision: url.searchParams.get('revision') || '',
          body,
        });
        return reply(
          route,
          ok({ path: filePath, size: String(body.content || '').length, revision: state.revision })
        );
      }
      if (method === 'DELETE') {
        calls.deleted.push({ kind: 'file', skillId, path: filePath });
        return reply(route, ok({ path: filePath, deleted: true }));
      }
      return reply(
        route,
        ok({
          path: filePath,
          content: FILE_CONTENT[filePath] || `# ${filePath}`,
          size: 120,
          kind: filePath.startsWith('scripts/') ? 'script' : 'reference',
        })
      );
    }

    const dataBrowseMatch = path.match(/^\/api\/admin\/skills\/([^/]+)\/data\/(tree|file)$/);
    if (dataBrowseMatch) {
      const id = decodeURIComponent(dataBrowseMatch[1]);
      const relative = url.searchParams.get('path') || '';
      if (dataBrowseMatch[2] === 'tree') {
        calls.dataTree.push({ id, path: relative });
        if (state.dataTreeBusy) {
          return reply(route, fail(409, '技能数据正在初始化，请等待完成后再浏览'));
        }
        return reply(route, ok(dataTreePayload(id, relative)));
      }
      calls.dataFile.push({ id, path: relative });
      return reply(route, ok(dataFilePayload(relative)));
    }

    const subMatch = path.match(
      /^\/api\/admin\/skills\/([^/]+)\/(overview|bindings|versions|data|data\/init)$/
    );
    if (subMatch) {
      const id = decodeURIComponent(subMatch[1]);
      const sub = subMatch[2];
      if (sub === 'overview') return reply(route, ok(overviewOf(id)));
      if (sub === 'bindings') {
        const bound = state.source === 'db' && id === 'poetry-skills';
        return reply(route, ok({ id, bindings: bound ? POETRY_BINDINGS : [] }));
      }
      if (sub === 'versions') return reply(route, ok(versionsPayload(id)));
      if (sub === 'data') return reply(route, ok(dataPayload(id)));
      calls.dataInit.push({ id, body: jsonBody(request) });
      return reply(route, ok(dataPayload(id)));
    }

    const versionMatch = path.match(/^\/api\/admin\/skills\/([^/]+)\/versions\/(\d+)(\/rollback)?$/);
    if (versionMatch) {
      const id = decodeURIComponent(versionMatch[1]);
      const versionNo = Number(versionMatch[2]);
      if (versionMatch[3]) {
        calls.rollbacks.push({ id, versionNo, revision: url.searchParams.get('revision') || '' });
        state.versionNo = versionNo;
        state.revision = `rev-${versionNo}`;
        return reply(route, ok({ ...detailOf(id), versionNo }));
      }
      const version = versionsPayload(id).versions.find((item) => item.versionNo === versionNo);
      return reply(route, ok({ ...version, content: SKILL_BODY }));
    }

    const detailMatch = path.match(/^\/api\/admin\/skills\/([^/]+)$/);
    if (detailMatch) {
      const id = decodeURIComponent(detailMatch[1]);
      if (method === 'PUT') {
        const input = jsonBody(request);
        const revision = url.searchParams.get('revision') || '';
        calls.updated.push({ id, body: input, revision });
        if (state.conflict) {
          state.versionNo = 8;
          state.revision = 'rev-8';
          return reply(route, fail(409, '技能已被他人修改', { currentRevision: 'rev-8' }));
        }
        state.versionNo += 1;
        state.revision = `rev-${state.versionNo}`;
        const detail = createdSkills.get(id);
        if (detail) {
          detail.content = input.content;
          detail.description = input.description;
          detail.versionNo = state.versionNo;
          detail.revision = state.revision;
          detail.updatedAt = Date.now();
        }
        return reply(route, ok(detailOf(id)));
      }
      if (method === 'DELETE') {
        calls.deleted.push({ kind: 'skill', id, revision: url.searchParams.get('revision') || '' });
        createdSkills.delete(id);
        return reply(route, ok({ id, deleted: true }));
      }
      return reply(route, ok(detailOf(id)));
    }

    // 其余管理台接口在视觉基线里只需要一个空壳，避免页面报错。
    return reply(route, ok({}));
  }

  async function install(page) {
    // 用 pathname 判断而非 glob：'**/api/**' 会误伤 Vite 的 /src/api/http.js 模块请求。
    await page.route(
      (url) => url.pathname.startsWith('/api/'),
      (route) => handle(route)
    );
  }

  return { state, calls, install, handle, detailOf };
}

export { SHORT_BODY, ME };
