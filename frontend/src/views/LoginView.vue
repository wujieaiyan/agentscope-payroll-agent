<script setup>
import { ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { login } from '../stores/auth';

const router = useRouter();
const route = useRoute();
const username = ref('');
const password = ref('');
const error = ref('');
const busy = ref(false);

async function submit() {
  if (busy.value) return;
  error.value = '';
  busy.value = true;
  try {
    await login(username.value.trim(), password.value);
    router.push(route.query.redirect || '/chat');
  } catch (e) {
    error.value = e.message || '登录失败';
    password.value = '';
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <form class="login-card" @submit.prevent="submit">
      <div class="login-brand">
        <div class="brand-mark">协</div>
        <h1>协同智能体</h1>
        <p class="hint">Payroll Agent Console · 多用户协同</p>
      </div>
      <label>
        用户名
        <input v-model="username" autocomplete="username" required autofocus />
      </label>
      <label>
        密码
        <input v-model="password" type="password" autocomplete="current-password" required />
      </label>
      <p v-if="error" class="login-error">{{ error }}</p>
      <button class="primary" type="submit" :disabled="busy">
        {{ busy ? '登录中…' : '登录' }}
      </button>
      <p class="hint login-hint">首次启动默认管理员 admin / admin123，登录后请立即修改密码。</p>
    </form>
  </main>
</template>
