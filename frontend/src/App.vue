<script setup>
import { dismiss, toasts } from './stores/toast';
</script>

<template>
  <router-view />
  <div class="toast-stack">
    <div
      v-for="item in toasts"
      :key="item.id"
      class="toast"
      :class="item.type"
      @click="dismiss(item.id)"
    >
      {{ item.message }}
    </div>
  </div>
</template>
