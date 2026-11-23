<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../api/http';
import { auth, can, logout, markPasswordChanged } from '../stores/auth';
import { toast } from '../stores/toast';
import { defaultPathForWorkspace, landingPath, menuFromRoutes } from '../router/workspaces';

const router = useRouter();
const route = useRoute();
const collapsed = ref(false);
const collapsedGroups = reactive({});
const showPassword = ref(false);
const passwordSaving = ref(false);
const passwordForm = reactive({ old_password: '', new_password: '', confirm: '' });
const forcing = computed(() => auth.mustChangePassword);
const passwordModalOpen = computed(() => forcing.value || showPassword.value);

const chatItem = {
  path: '/chat',
  label: '对话',
  icon: 'M21 12a8 8 0 0 1-11.6 7.1L4 21l1.9-5.4A8 8 0 1 1 21 12z',
};

const groups = computed(() => menuFromRoutes(router.getRoutes(), can, auth.profileEnabled));

const initialWorkspace =
  route.meta.workspace ||
  router.resolve(landingPath(auth.user)).meta.workspace ||
  (groups.value[0] && groups.value[0].key);
const activeWorkspace = ref(initialWorkspace);

watch(
  () => route.meta.workspace,
  (workspace) => {
    if (workspace && groups.value.some((group) => group.key === workspace)) {
      activeWorkspace.value = workspace;
    }
  }
);

const activeGroup = computed(
  () => groups.value.find((group) => group.key === activeWorkspace.value) || groups.value[0]
);

function switchWorkspace(group) {
  activeWorkspace.value = group.key;
  router.push(defaultPathForWorkspace(groups.value, group.key));
}

const displayName = computed(() => (auth.user ? auth.user.display_name || auth.user.username : ''));
const roleName = computed(() => (auth.user ? auth.user.role_name || auth.user.role_code : ''));

function toggleGroup(title) {
  collapsedGroups[title] = !collapsedGroups[title];
}

function openPassword() {
  Object.assign(passwordForm, { old_password: '', new_password: '', confirm: '' });
  showPassword.value = true;
}

function closePassword() {
  if (forcing.value) return;
  showPassword.value = false;
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
    markPasswordChanged();
    Object.assign(passwordForm, { old_password: '', new_password: '', confirm: '' });
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
      <div v-if="!collapsed && groups.length > 1" class="ws-switch">
        <button
          v-for="group in groups"
          :key="group.key"
          type="button"
          class="ws-tab"
          :class="{ active: group.key === activeWorkspace }"
          :title="group.title"
          @click="switchWorkspace(group)"
        >
          {{ group.short }}
        </button>
      </div>
      <nav class="nav">
        <router-link :to="chatItem.path" class="nav-item nav-chat" :title="chatItem.label">
          <span class="nav-icon">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path :d="chatItem.icon" />
            </svg>
          </span>
          <span v-if="!collapsed">{{ chatItem.label }}</span>
        </router-link>
        <section
          v-if="activeGroup"
          :key="activeGroup.key"
          class="nav-group"
          :class="{ collapsed: collapsedGroups[activeGroup.key] && !collapsed }"
        >
          <button
            type="button"
            class="nav-group-title"
            :aria-expanded="!collapsedGroups[activeGroup.key]"
            :title="activeGroup.title"
            @click="toggleGroup(activeGroup.key)"
          >
            <span class="nav-caret"></span>
            <span class="nav-group-text">{{ activeGroup.title }}</span>
          </button>
          <div class="nav-children">
            <router-link
              v-for="item in activeGroup.items"
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

    <div v-if="passwordModalOpen" class="modal-mask" @click.self="closePassword">
      <div class="modal" style="width: min(420px, 96vw)">
        <div class="modal-head">
          <h3>{{ forcing ? '首次登录需修改密码' : '修改密码' }}</h3>
          <button v-if="!forcing" class="subtle sm" @click="closePassword">关闭</button>
        </div>
        <div class="modal-body">
          <p v-if="forcing" class="hint">为保障账号安全，修改初始密码后才能使用管理台。</p>
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
          <button v-if="forcing" class="ghost" @click="signOut">退出登录</button>
          <button v-else class="ghost" @click="closePassword">取消</button>
          <button class="primary" :disabled="passwordSaving" @click="savePassword">
            {{ passwordSaving ? '提交中…' : '确认修改' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ws-switch {
  display: flex;
  gap: 4px;
  padding: 2px 8px 8px;
}
.ws-tab {
  flex: 1;
  padding: 5px 0;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 6px;
  background: transparent;
  color: var(--sidebar-ink);
  font-size: 12px;
  cursor: pointer;
}
.ws-tab:hover { color: #fff; background: rgba(255, 255, 255, 0.08); }
.ws-tab.active {
  color: #fff;
  background: rgba(45, 140, 240, 0.3);
  border-color: rgba(45, 140, 240, 0.6);
}
.nav-chat { margin: 0 8px 6px; }
</style>
