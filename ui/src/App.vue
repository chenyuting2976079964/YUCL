<template>
  <div class="wrap">
    <!-- ============ 左栏：人物·背景 builder ============ -->
    <div class="col left">
      <div class="head">
        <div class="logo">
          <svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="8.5" cy="8" r="3"/><path d="M3 20c0-3.3 2.7-6 5.5-6S14 16.7 14 20"/><path d="M15 5.5A3.5 3.5 0 0 1 15 12M18 20c0-2.4 1-4.5 3-5.5"/></svg>
        </div>
        <div class="title">YUCL</div>
        <span class="badge"><span class="dot"></span>{{ provBadge }}</span>
      </div>
      <div class="sub">AI 短视频工作室 · 人物·背景成片 · 本地免费 · 独立窗口运行</div>
      <div v-if="store.backendWarn" class="err" style="margin-top:8px">{{ store.backendWarn }}</div>

      <VoiceSourceCard ref="voiceCard" />
      <BgmExportCard ref="bgmCard" />

      <ScriptPanel :script-to-form="scriptToForm" @applied="onScriptApplied" />
      <ProviderConfigPanel ref="provPanel" />
      <CharBgForm ref="charbgFormRef" />
      <MaintenancePanel />
    </div>

    <!-- ============ 右栏：播放器 + 历史 ============ -->
    <div class="col right">
      <PlayerPanel />
    </div>

    <InPageModal />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, provide, ref, computed } from "vue";
import { api } from "./api";
import { useAppStore } from "./store";
import InPageModal from "./components/InPageModal.vue";
import VoiceSourceCard from "./components/VoiceSourceCard.vue";
import BgmExportCard from "./components/BgmExportCard.vue";
import ScriptPanel from "./components/ScriptPanel.vue";
import ProviderConfigPanel from "./components/ProviderConfigPanel.vue";
import CharBgForm from "./components/CharBgForm.vue";
import MaintenancePanel from "./components/MaintenancePanel.vue";
import PlayerPanel from "./components/PlayerPanel.vue";
import type { ScriptDoc } from "./api";

const store = useAppStore();
const voiceCard = ref<InstanceType<typeof VoiceSourceCard> | null>(null);
const bgmCard = ref<InstanceType<typeof BgmExportCard> | null>(null);
const charbgFormRef = ref<InstanceType<typeof CharBgForm> | null>(null);
const provPanel = ref<InstanceType<typeof ProviderConfigPanel> | null>(null);

// 供 CharBgForm 提交时读取旁白/BGM 负载
provide("yuclCards", {
  voice: () => voiceCard.value,
  bgm: () => bgmCard.value
} as any);

const provBadge = ref("连接中…");
let pollIv: ReturnType<typeof setInterval> | null = null;

function scriptToForm(s: ScriptDoc): string {
  return charbgFormRef.value?.applyScript(s) || "";
}

function onScriptApplied(s: ScriptDoc, msg: string) {
  // 由 ScriptPanel 内部展示状态；此钩子预留
}

onMounted(async () => {
  try {
    await store.loadOptions();
  } catch { /* backendWarn 已显示 */ }
  store.loadVoices();
  store.loadMusic();
  store.loadJobs();
  const poll = async () => {
    try {
      const j = await api.agnesaStatus();
      provBadge.value = j.ok
        ? "在线 · " + (j.provider || "内置")
        : "离线 · " + (j.reason || "供应商未就绪");
    } catch {
      provBadge.value = "离线";
    }
  };
  poll();
  pollIv = setInterval(poll, 30000);
});
onBeforeUnmount(() => {
  if (pollIv) clearInterval(pollIv);
});
</script>
