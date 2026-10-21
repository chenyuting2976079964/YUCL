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
      <div class="sub">{{ t("app_sub") }}</div>
      <div v-if="store.backendWarn" class="err" style="margin-top:8px">{{ store.backendWarn }}</div>

      <!-- 基本流程（主流程）：人物图 → 场景图 → 内容描述 → 生成视频 -->
      <CharBgForm ref="charbgFormRef" />

      <details class="cb-details" open>
        <summary>旁白与 BGM（可选）</summary>
        <VoiceSourceCard ref="voiceCard" />
        <BgmExportCard ref="bgmCard" />
      </details>
      <details class="cb-details">
        <summary>一键剧本（LLM 写故事 + 台词）</summary>
        <ScriptPanel :script-to-form="scriptToForm" @applied="onScriptApplied" />
      </details>
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

// 全局钩子：原生标题栏（JavaFX）的「设置」齿轮按钮经 WebEngine.executeScript 调这里打开设置面板
(window as unknown as { __yuclOpenSettings?: () => void }).__yuclOpenSettings = () => {
  showSettings.value = true;
};

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
