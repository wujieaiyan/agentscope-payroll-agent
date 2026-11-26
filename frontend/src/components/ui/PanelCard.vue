<script setup>
defineProps({
  title: { type: String, default: '' },
  flush: { type: Boolean, default: false },
});
</script>

<template>
  <section class="panel-card">
    <header v-if="title || $slots.actions || $slots.hint" class="panel-card-head">
      <div class="panel-card-title">
        <h3 v-if="title">{{ title }}</h3>
        <slot name="hint" />
      </div>
      <slot name="actions" />
    </header>
    <div class="panel-card-body" :class="{ 'panel-card-body--flush': flush }">
      <slot />
    </div>
  </section>
</template>
