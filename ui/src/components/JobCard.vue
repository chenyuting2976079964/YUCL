<template>
  <div class="job" :class="{ active, running: running }" :data-running="running ? '1' : ''" @click="select">
    <button v-if="!running" type="button" class="job-del" title="删除该作品（含生成的视频文件）"
            @click.stop="$emit('delete', job.id)">×</button>
    <div class="thumb" :class="{ 'thumb-running': running }" :style="{ '--h': hue(job.id) }">
      <template v-if="running">
        <span class="job-thumb-ico">⏳</span>
        <span class="dur">{{ pct }}%</span>
      </template>
      <template v-else-if="failed">
        <span class="job-thumb-ico">{{ job.status === 'cancelled' ? '⏸' : '✕' }}</span>
        <span class="dur">{{ job.status === 'cancelled' ? '已取消' : '失败' }}</span>
      </template>
      <template v-else>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:26px;height:26px"><polygon points="6 3 20 12 6 21 6 3"/></svg>
        <span class="dur">{{ job.duration_s ?? "" }}s</span>
      </template>
    </div>
    <div class="t">{{ job.topic || (running ? '生成中' : '任务') }}</div>
    <div class="m">
      <template v-if="running">
        <div class="job-prog" style="width:100%"><div :style="{ width: pct + '%' }"></div></div>
        <div class="hint" style="font-size:11px">{{ job.progress?.message || '渲染中…' }}</div>
        <button type="button" class="job-act warn" @click.stop="$emit('cancel', job.id)">取消任务</button>
      </template>
      <template v-else-if="failed">
        <div class="hint" style="font-size:11px">{{ job.status === 'cancelled' ? '已手动取消' : (job.error || '生成失败') }}</div>
        <button type="button" class="job-act" @click.stop="$emit('retry', job.id)">重试</button>
      </template>
      <template v-else>
        <span class="tag">{{ job.style || 'video' }}</span> · {{ job.duration_s ?? "" }}s
        <span v-if="job.voice_warnings?.length" :title="job.voice_warnings.join('；')" style="color:var(--warn)">⚠</span>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { Job } from "../api";

const props = defineProps<{ job: Job; active?: boolean }>();
const emit = defineEmits<{
  (e: "select", id: string, meta?: unknown): void;
  (e: "cancel", id: string): void;
  (e: "retry", id: string): void;
  (e: "delete", id: string): void;
}>();

const running = computed(() => (props.job.status || "done") === "running");
const failed = computed(() => props.job.status === "failed" || props.job.status === "cancelled");
const pct = computed(() => Math.max(0, Math.min(100, props.job.progress?.percent ?? 0)));

function hue(id: string) {
  let h = 210;
  for (const c of String(id ?? "")) h = (h * 31 + c.charCodeAt(0)) % 360;
  return h;
}
function select() {
  if (running.value) return;
  emit("select", props.job.id, { duration_s: props.job.duration_s });
}
</script>
