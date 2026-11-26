<script setup>
import { computed } from 'vue';

const props = defineProps({
  agents: { type: Array, default: () => [] },
  max: { type: Number, default: 3 },
});

const shown = computed(() => props.agents.slice(0, props.max));
const rest = computed(() => Math.max(0, props.agents.length - props.max));

function initial(agent) {
  const name = String(agent?.name || agent?.agentId || '?').trim();
  return name ? name[0].toUpperCase() : '?';
}
</script>

<template>
  <span class="agent-avatars" :aria-label="`${agents.length} 个智能体引用`">
    <span
      v-for="(agent, index) in shown"
      :key="agent.agentId || index"
      class="agent-avatar"
      :class="`a${(index % 4) + 1}`"
      :title="agent.name || agent.agentId"
    >
      {{ initial(agent) }}
    </span>
    <span v-if="rest" class="agent-avatar agent-avatar--rest" :title="`还有 ${rest} 个`">
      +{{ rest }}
    </span>
  </span>
</template>

<style scoped>
.agent-avatars {
  display: inline-flex;
  align-items: center;
}
.agent-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  margin-left: -6px;
  border: 2px solid var(--surface, #fff);
  border-radius: 50%;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}
.agent-avatar:first-child {
  margin-left: 0;
}
.agent-avatar--rest {
  background: var(--surface-3, #f2f4f7);
  color: var(--muted, #6b7484);
  font-size: 10.5px;
}
.agent-avatar.a1 {
  background: #edf3ff;
  color: #2457c4;
}
.agent-avatar.a2 {
  background: #e8f5ee;
  color: #18794e;
}
.agent-avatar.a3 {
  background: #fff6e3;
  color: #9a6700;
}
.agent-avatar.a4 {
  background: #f3edff;
  color: #6b3fc4;
}
</style>
