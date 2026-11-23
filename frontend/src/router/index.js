import { createRouter, createWebHistory } from 'vue-router';
import { auth, loadMe } from '../stores/auth';
import { landingPath } from './workspaces';

const ICONS = {
  chat: 'M21 12a8 8 0 0 1-11.6 7.1L4 21l1.9-5.4A8 8 0 1 1 21 12z',
  agents: 'M12 3v2M8 7h8a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3v-7a3 3 0 0 1 3-3zM9 12h.01M15 12h.01M9.5 16h5',
  flows: 'M4 5h6v5H4zM14 14h6v5h-6zM10 7.5h2.5A2.5 2.5 0 0 1 15 10v4',
  skills: 'M4 5a2 2 0 0 1 2-2h13v18H6a2 2 0 0 1-2-2zM8 3v18M12 8h3M12 12h3',
  team: 'M16 20a4 4 0 0 0-8 0M12 12a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM20 20a3 3 0 0 0-3-3M17.5 12a2.3 2.3 0 1 0 0-4.6',
  employees: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
  reviews: 'M4 19V5M4 19h16M8 16v-4M12 16V8M16 16v-6',
  attendance: 'M7 3v3M17 3v3M4 8h16M5 21h14a1 1 0 0 0 1-1V6a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1zM9 13l2 2 4-4',
  runs: 'M21 8l-9-5-9 5 9 5 9-5zM3 8v8l9 5 9-5V8M12 13v8',
  tasks: 'M9 5h10M9 12h10M9 19h10M4 5l1 1 2-2M4 12l1 1 2-2M4 19l1 1 2-2',
  policies: 'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M9 13h6M9 17h4',
  models: 'M4 7h16M4 12h16M4 17h10M18 15v4M16 17h4',
  users: 'M12 3l8 3v6c0 5-3.4 8.2-8 9-4.6-.8-8-4-8-9V6zM9 12l2 2 4-4',
  audit: 'M4 5h16M4 12h10M4 19h16M18 10v4M18 10h3v4h-3',
  usage: 'M4 20h16M6 20V10M11 20V5M16 20v-6M21 20V8',
  kb: 'M12 3l9 5-9 5-9-5 9-5zM3 12l9 5 9-5M3 16l9 5 9-5',
  profile: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v10M8 11h8',
};

function workspacePage(path, name, file, title, workspace, menu, extra = {}) {
  return {
    path,
    name,
    component: () => import(`../views/admin/${file}.vue`),
    meta: {
      title,
      workspace,
      menu: { label: menu.label, icon: ICONS[menu.icon], order: menu.order, workspace, permission: extra.permission, feature: extra.feature },
    },
  };
}

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('../components/AppLayout.vue'),
    children: [
      { path: '', name: 'home', component: { render: () => null }, meta: { title: '' } },
      { path: 'chat', name: 'chat', component: () => import('../views/ChatView.vue'), meta: { title: '对话' } },
      workspacePage('platform/agents', 'agents', 'AgentsView', '智能体', 'platform', { label: '智能体', icon: 'agents', order: 10 }, { permission: 'agent:manage' }),
      workspacePage('platform/flows', 'flows', 'FlowsView', '流程编排', 'platform', { label: '流程编排', icon: 'flows', order: 20 }, { permission: 'flow:manage' }),
      workspacePage('platform/skills', 'skills', 'SkillsView', '技能', 'platform', { label: '技能', icon: 'skills', order: 30 }, { permission: 'skill:manage' }),
      workspacePage('platform/team', 'team', 'TeamView', '团队运行', 'platform', { label: '团队运行', icon: 'team', order: 40 }),
      { path: 'platform/kb', name: 'kb', component: () => import('../kb/views/SeriesListView.vue'), meta: { title: '知识库', workspace: 'platform', menu: { label: '知识库', icon: ICONS.kb, order: 50, workspace: 'platform', permission: 'kb:read' } } },
      { path: 'platform/kb/create', name: 'kb-create', component: () => import('../kb/views/SeriesCreateView.vue'), meta: { title: '新建知识库', workspace: 'platform' } },
      { path: 'platform/kb/:id(\\d+)', name: 'kb-detail', component: () => import('../kb/views/SeriesDetailView.vue'), meta: { title: '知识库详情', workspace: 'platform' } },
      workspacePage('payroll/employees', 'employees', 'EmployeesView', '员工', 'payroll', { label: '员工', icon: 'employees', order: 10 }, { permission: 'employee:read' }),
      workspacePage('payroll/reviews', 'reviews', 'ReviewsView', '绩效考核', 'payroll', { label: '绩效考核', icon: 'reviews', order: 20 }, { permission: 'review:read' }),
      workspacePage('payroll/attendance', 'attendance', 'AttendanceView', '考勤管理', 'payroll', { label: '考勤', icon: 'attendance', order: 30 }, { permission: 'attendance:read' }),
      workspacePage('payroll/runs', 'runs', 'RunsView', '工资批次', 'payroll', { label: '工资批次', icon: 'runs', order: 40 }, { permission: 'payroll:read' }),
      workspacePage('payroll/tasks', 'tasks', 'TasksView', '我的待办', 'payroll', { label: '我的待办', icon: 'tasks', order: 50 }, { permission: 'payroll:approve' }),
      workspacePage('payroll/policies', 'policies', 'PoliciesView', '政策', 'payroll', { label: '政策', icon: 'policies', order: 60 }, { permission: 'policy:read' }),
      workspacePage('platform/models', 'models', 'ModelsView', '模型配置', 'governance', { label: '模型配置', icon: 'models', order: 10 }, { permission: 'agent:manage' }),
      workspacePage('platform/users', 'users', 'UsersView', '用户与角色', 'governance', { label: '用户与角色', icon: 'users', order: 20 }, { permission: 'user:manage' }),
      workspacePage('platform/audit', 'audit', 'AuditView', '审计日志', 'governance', { label: '审计日志', icon: 'audit', order: 30 }, { permission: 'audit:read' }),
      workspacePage('platform/usage', 'usage', 'UsageView', '用量看板', 'governance', { label: '用量看板', icon: 'usage', order: 40 }, { permission: 'usage:read' }),
      { path: 'platform/profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { title: '我的工作偏好', workspace: 'governance', menu: { label: '我的工作偏好', icon: ICONS.profile, order: 50, workspace: 'governance', permission: 'profile:read', feature: 'profile' } } },
      { path: 'agents', redirect: '/platform/agents' },
      { path: 'flows', redirect: '/platform/flows' },
      { path: 'skills', redirect: '/platform/skills' },
      { path: 'team', redirect: '/platform/team' },
      { path: 'kb', redirect: '/platform/kb' },
      { path: 'kb/create', redirect: '/platform/kb/create' },
      { path: 'kb/:id(\\d+)', redirect: (to) => `/platform/kb/${to.params.id}` },
      { path: 'employees', redirect: '/payroll/employees' },
      { path: 'reviews', redirect: '/payroll/reviews' },
      { path: 'attendance', redirect: '/payroll/attendance' },
      { path: 'runs', redirect: '/payroll/runs' },
      { path: 'tasks', redirect: '/payroll/tasks' },
      { path: 'policies', redirect: '/payroll/policies' },
      { path: 'models', redirect: '/platform/models' },
      { path: 'users', redirect: '/platform/users' },
      { path: 'audit', redirect: '/platform/audit' },
      { path: 'usage', redirect: '/platform/usage' },
      { path: 'profile', redirect: '/platform/profile' },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach(async (to) => {
  if (!auth.loaded) {
    await loadMe();
  }
  if (to.meta.public) {
    return auth.user ? { path: landingPath(auth.user) } : true;
  }
  if (!auth.user) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  if (to.name === 'home') {
    return landingPath(auth.user);
  }
  document.title = to.meta.title ? `${to.meta.title} · 协同智能体` : '协同智能体';
  return true;
});

export default router;
