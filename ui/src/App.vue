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
        <button class="setbtn" type="button" :title="t('settings')" @click="showSettings = true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.09a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.09a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.09a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"/></svg>
          <span>{{ t("settings") }}</span>
        </button>
      </div>
      <div class="sub">{{ t("app_sub") }}</div>
      <div v-if="store.backendWarn" class="err" style="margin-top:8px">{{ store.backendWarn }}</div>

      <VoiceSourceCard ref="voiceCard" />
      <BgmExportCard ref="bgmCard" />

      <ScriptPanel :script-to-form="scriptToForm" @applied="onScriptApplied" />
      <CharBgForm ref="charbgFormRef" />
      <MaintenancePanel />
    </div>

    <!-- ============ 右栏：播放器 + 历史 ============ -->
    <div class="col right">
      <PlayerPanel />
    </div>

    <InPageModal />
    <SettingsModal v-if="showSettings" @close="showSettings = false" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, provide, ref } from "vue";
import { api } from "./api";
import { useAppStore } from "./store";
import InPageModal from "./components/InPageModal.vue";
import VoiceSourceCard from "./components/VoiceSourceCard.vue";
import BgmExportCard from "./components/BgmExportCard.vue";
import ScriptPanel from "./components/ScriptPanel.vue";
import CharBgForm from "./components/CharBgForm.vue";
import MaintenancePanel from "./components/MaintenancePanel.vue";
import PlayerPanel from "./components/PlayerPanel.vue";
import SettingsModal from "./components/SettingsModal.vue";
import type { ScriptDoc } from "./api";
import { applyAppearance, appearance, i18n, t } from "./i18n";

const store = useAppStore();
const voiceCard = ref<InstanceType<typeof VoiceSourceCard> | null>(null);
const bgmCard = ref<InstanceType<typeof BgmExportCard> | null>(null);
const charbgFormRef = ref<InstanceType<typeof CharBgForm> | null>(null);
const showSettings = ref(false);

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
  applyAppearance({ ...appearance });
  // 页面先于后端就绪时自动重试（服务还在起 → 界面立即可用，不再干等）
  for (let i = 0; i < 120; i++) {
    try {
      await store.loadOptions();
      break;
    } catch {
      provBadge.value = "服务启动中…";
      await new Promise(r => setTimeout(r, 400));
    }
  }
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
