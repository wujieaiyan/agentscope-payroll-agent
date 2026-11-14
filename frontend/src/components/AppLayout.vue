<script setup>
import { computed, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../api/http';
import { auth, can, logout } from '../stores/auth';
import { toast } from '../stores/toast';

const router = useRouter();
const route = useRoute();
const collapsed = ref(false);
const collapsedGroups = reactive({});
const showPassword = ref(false);
const passwordSaving = ref(false);
const passwordForm = reactive({ old_password: '', new_password: '', confirm: '' });

const groups = [
  {
    title: '协同',
    items: [
      { path: '/chat', label: '对话', icon: 'M21 12a8 8 0 0 1-11.6 7.1L4 21l1.9-5.4A8 8 0 1 1 21 12z' },
      { path: '/agents', label: '智能体', icon: 'M12 3v2M8 7h8a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3v-7a3 3 0 0 1 3-3zM9 12h.01M15 12h.01M9.5 16h5' },
      { path: '/flows', label: '流程编排', icon: 'M4 5h6v5H4zM14 14h6v5h-6zM10 7.5h2.5A2.5 2.5 0 0 1 15 10v4' },
      { path: '/skills', label: '技能', icon: 'M4 5a2 2 0 0 1 2-2h13v18H6a2 2 0 0 1-2-2zM8 3v18M12 8h3M12 12h3' },
      { path: '/team', label: '团队运行', icon: 'M16 20a4 4 0 0 0-8 0M12 12a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM20 20a3 3 0 0 0-3-3M17.5 12a2.3 2.3 0 1 0 0-4.6' },
    ],
  },
  {
    title: '薪酬',
    items: [
      { path: '/employees', label: '员工', icon: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', permission: 'employee:read' },
      { path: '/reviews', label: '绩效考核', icon: 'M4 19V5M4 19h16M8 16v-4M12 16V8M16 16v-6', permission: 'review:read' },
      { path: '/attendance', label: '考勤', icon: 'M7 3v3M17 3v3M4 8h16M5 21h14a1 1 0 0 0 1-1V6a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1zM9 13l2 2 4-4', permission: 'attendance:read' },
      { path: '/runs', label: '工资批次', icon: 'M21 8l-9-5-9 5 9 5 9-5zM3 8v8l9 5 9-5V8M12 13v8', permission: 'payroll:read' },
      { path: '/tasks', label: '我的待办', icon: 'M9 5h10M9 12h10M9 19h10M4 5l1 1 2-2M4 12l1 1 2-2M4 19l1 1 2-2', permission: 'payroll:approve' },
      { path: '/policies', label: '政策', icon: 'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M9 13h6M9 17h4', permission: 'policy:read' },
    ],
  },
  {
    title: '系统',
    items: [
      { path: '/users', label: '用户与角色', icon: 'M12 3l8 3v6c0 5-3.4 8.2-8 9-4.6-.8-8-4-8-9V6zM9 12l2 2 4-4' },
      { path: '/audit', label: '审计日志', icon: 'M4 5h16M4 12h10M4 19h16M18 10v4M18 10h3v4h-3', permission: 'audit:read' },
      { path: '/kb', label: '知识库', icon: 'M12 3l9 5-9 5-9-5 9-5zM3 12l9 5 9-5M3 16l9 5 9-5', permission: 'kb:read' },
    ],
  },
];

const visibleGroups = computed(() =>
  groups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => !item.permission || can(item.permission)),
    }))
    .filter((group) => group.items.length)
);

const displayName = computed(() => (auth.user ? auth.user.display_name || auth.user.username : ''));
const roleName = computed(() => (auth.user ? auth.user.role_name || auth.user.role_code : ''));

function toggleGroup(title) {
  collapsedGroups[title] = !collapsedGroups[title];
}

function openPassword() {
  Object.assign(passwordForm, { old_password: '', new_password: '', confirm: '' });
  showPassword.value = true;
}

async function savePassword() {
  if (passwordForm.new_password.length < 8) {
    toast('新密码至少 8 位', 'error');
    return;
  }
  if (passwordForm.new_password !== passwordForm.confirm) {
    toast('两次输入的新密码不一致', 'error');
    return;
  }
  passwordSaving.value = true;
  try {
    await api('/api/auth/password', {
      method: 'POST',
      body: {
        old_password: passwordForm.old_password,
        new_password: passwordForm.new_password,
      },
    });
    showPassword.value = false;
    toast('密码已修改', 'success');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    passwordSaving.value = false;
  }
}

async function signOut() {
  await logout();
  router.push('/login');
}
</script>

<template>
  <div class="shell" :class="{ collapsed }">
    <aside class="sidebar">
      <div class="brand" @click="router.push('/chat')">
        <span class="brand-mark">协</span>
        <span v-if="!collapsed" class="brand-text">
          <strong>协同智能体</strong>
          <small>Payroll Console</small>
        </span>
      </div>
      <nav class="nav">
        <section
          v-for="group in visibleGroups"
          :key="group.title"
          class="nav-group"
          :class="{ collapsed: collapsedGroups[group.title] && !collapsed }"
        >
          <button
            type="button"
            class="nav-group-title"
            :aria-expanded="!collapsedGroups[group.title]"
            :title="group.title"
            @click="toggleGroup(group.title)"
          >
            <span class="nav-caret"></span>
            <span class="nav-group-text">{{ group.title }}</span>
          </button>
          <div class="nav-children">
            <router-link
              v-for="item in group.items"
              :key="item.path"
              :to="item.path"
              class="nav-item"
              :title="item.label"
            >
              <span class="nav-icon">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path :d="item.icon" />
                </svg>
              </span>
              <span v-if="!collapsed">{{ item.label }}</span>
            </router-link>
          </div>
        </section>
      </nav>
      <button class="collapse-btn" @click="collapsed = !collapsed">
        {{ collapsed ? '»' : '«' }}
      </button>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span class="crumb-root">协同智能体</span>
          <span class="crumb-sep">/</span>
          <strong>{{ route.meta.title || '' }}</strong>
        </div>
        <div class="user-area">
          <div class="user-card">
            <span class="avatar">{{ (displayName || '用').slice(0, 1) }}</span>
            <span class="user-meta">
              <strong>{{ displayName }}</strong>
              <small>{{ roleName }}</small>
            </span>
          </div>
          <button class="ghost sm" @click="openPassword">修改密码</button>
          <button class="ghost" @click="signOut">退出</button>
        </div>
      </header>
      <main class="content" :class="{ flush: route.name === 'chat' }">
        <router-view />
      </main>
    </div>

    <div v-if="showPassword" class="modal-mask" @click.self="showPassword = false">
      <div class="modal" style="width: min(420px, 96vw)">
        <div class="modal-head">
          <h3>修改密码</h3>
          <button class="subtle sm" @click="showPassword = false">关闭</button>
        </div>
        <div class="modal-body">
          <label class="field">
            当前密码
            <input v-model="passwordForm.old_password" type="password" autocomplete="current-password" />
          </label>
          <label class="field">
            新密码（至少 8 位）
            <input v-model="passwordForm.new_password" type="password" autocomplete="new-password" />
          </label>
          <label class="field">
            确认新密码
            <input v-model="passwordForm.confirm" type="password" autocomplete="new-password" />
          </label>
        </div>
        <div class="modal-foot">
          <button class="ghost" @click="showPassword = false">取消</button>
          <button class="primary" :disabled="passwordSaving" @click="savePassword">
            {{ passwordSaving ? '提交中…' : '确认修改' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
