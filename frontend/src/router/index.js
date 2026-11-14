import { createRouter, createWebHistory } from 'vue-router';
import { auth, loadMe } from '../stores/auth';

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
      { path: '', redirect: '/chat' },
      { path: 'chat', name: 'chat', component: () => import('../views/ChatView.vue'), meta: { title: '对话' } },
      { path: 'employees', name: 'employees', component: () => import('../views/admin/EmployeesView.vue'), meta: { title: '员工' } },
      { path: 'reviews', name: 'reviews', component: () => import('../views/admin/ReviewsView.vue'), meta: { title: '绩效考核' } },
      { path: 'attendance', name: 'attendance', component: () => import('../views/admin/AttendanceView.vue'), meta: { title: '考勤管理' } },
      { path: 'runs', name: 'runs', component: () => import('../views/admin/RunsView.vue'), meta: { title: '工资批次' } },
      { path: 'tasks', name: 'tasks', component: () => import('../views/admin/TasksView.vue'), meta: { title: '我的待办' } },
      { path: 'agents', name: 'agents', component: () => import('../views/admin/AgentsView.vue'), meta: { title: '智能体' } },
      { path: 'flows', name: 'flows', component: () => import('../views/admin/FlowsView.vue'), meta: { title: '流程编排' } },
      { path: 'skills', name: 'skills', component: () => import('../views/admin/SkillsView.vue'), meta: { title: '技能' } },
      { path: 'team', name: 'team', component: () => import('../views/admin/TeamView.vue'), meta: { title: '团队运行' } },
      { path: 'users', name: 'users', component: () => import('../views/admin/UsersView.vue'), meta: { title: '用户与角色' } },
      { path: 'audit', name: 'audit', component: () => import('../views/admin/AuditView.vue'), meta: { title: '审计日志' } },
      { path: 'kb', name: 'kb', component: () => import('../views/admin/KbView.vue'), meta: { title: '知识库' } },
      { path: 'policies', name: 'policies', component: () => import('../views/admin/PoliciesView.vue'), meta: { title: '政策' } },
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
    return auth.user ? { path: '/chat' } : true;
  }
  if (!auth.user) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  document.title = to.meta.title ? `${to.meta.title} · 协同智能体` : '协同智能体';
  return true;
});

export default router;
