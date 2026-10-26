<template>
  <div class="vcard">
    <div class="vh">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>
      BGM 与导出
    </div>
    <div class="field">
      <label class="lbl">BGM 曲库 <span class="hint">生成时铺在旁白下面</span></label>
      <select v-model="bgm">
        <option value="">— 不加 BGM —</option>
        <option v-for="m in store.music" :key="m.name" :value="m.name">{{ m.name }} · {{ m.duration_s ?? 0 }}s</option>
      </select>
    </div>
    <div class="field">
      <label class="lbl">BGM 音量 <b style="color:var(--fg)">{{ Math.round(bgmVol * 100) }}%</b></label>
      <input type="range" v-model.number="bgmVol" min="0" max="0.6" step="0.02">
    </div>
    <div class="upl">
      <input type="file" ref="up" accept="audio/*,.mp3,.wav,.m4a,.aac,.flac,.ogg" @change="upload">
      <span>上传到 BGM 曲库（≤50MB）</span>
    </div>
    <div style="display:flex;gap:8px;margin-top:6px;flex-wrap:wrap">
      <button type="button" class="cb-addbtn" style="width:auto;padding:8px 14px" @click="preview">试听所选</button>
      <button v-if="bgm" type="button" class="cb-addbtn" style="width:auto;padding:8px 14px" @click="remove">删除所选 BGM</button>
    </div>
    <audio ref="prev" controls style="width:100%;margin-top:6px"></audio>
    <div class="row" style="margin-top:10px">
      <div class="field">
        <label class="lbl">导出分辨率</label>
        <select v-model="quality">
          <option value="2k">2K（AI 生成最高画质）</option>
          <option value="1080p">1080P（默认）</option>
          <option value="720p">720P（更小更快）</option>
        </select>
      </div>
      <div class="field">
        <label class="lbl">字幕大小</label>
        <select v-model="subSize">
          <option value="small">小</option>
          <option value="medium">中</option>
          <option value="large">大</option>
        </select>
      </div>
    </div>
    <div class="status" style="min-height:14px">{{ status }}</div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { api, baseUrl } from "../api";
import { useAppStore } from "../store";
import { uiConfirm } from "../dialogs";

const store = useAppStore();
const bgm = ref("");
const bgmVol = ref(0.18);
const quality = ref("1080p");
const subSize = ref("medium");
const status = ref("");
const prev = ref<HTMLAudioElement | null>(null);
const up = ref<HTMLInputElement | null>(null);

function sync() {
  const p = prev.value;
  if (!p) return;
  if (bgm.value) {
    p.src = baseUrl + "/music/preview?name=" + encodeURIComponent(bgm.value);
  } else {
    p.removeAttribute("src");
    p.load();
  }
}
watch(bgm, sync);

function preview() {
  sync();
  prev.value?.play().catch(() => {});
}
async function upload(e: Event) {
  const input = e.target as HTMLInputElement;
  const f = input.files?.[0];
  if (!f) return;
  status.value = "上传中 " + f.name + " …";
  try {
    const fd = new FormData();
    fd.append("file", f);
    const j = await api.uploadMusic(fd);
    status.value = j.ok ? "✅ 已加入曲库：" + j.music!.name : "✕ " + (j.detail || "上传失败");
    await store.loadMusic();
    if (j.ok) bgm.value = j.music!.name;
  } catch (ex: any) {
    status.value = "✕ 上传失败：" + (ex?.message || ex);
  }
  input.value = "";
}
async function remove() {
  const n = bgm.value;
  if (!n) return;
  if (!(await uiConfirm("删除 BGM 曲库中的 " + n + "？"))) return;
  try {
    const j = await api.deleteMusic(n);
    if (j.ok) {
      // 删的是当前选中曲 → 清引用并停预览，避免导出时 BGM 名指向已删文件（后端会静默丢 BGM）
      if (bgm.value === n) {
        bgm.value = "";
      }
      status.value = "✅ 已删除 " + n;
    } else {
      status.value = "✕ " + (j.detail || "删除失败");
    }
    await store.loadMusic();
  } catch (ex: any) {
    status.value = "✕ 删除失败：" + (ex?.message || ex);
  }
}

defineExpose({
  exportBgm: () => ({ bgm: bgm.value, bgmVol: bgmVol.value, quality: quality.value, subSize: subSize.value }),
  // 项目制：导出/回填当前 BGM 与导出设置
  getBgmState: () => ({ bgm: bgm.value, bgmVol: bgmVol.value, quality: quality.value, subSize: subSize.value }),
  applyBgmState(s: { bgm?: string; bgmVol?: number; quality?: string; subSize?: string }) {
    bgm.value = s?.bgm || "";
    bgmVol.value = typeof s?.bgmVol === "number" ? s.bgmVol : 0.18;
    quality.value = s?.quality || "1080p";
    subSize.value = s?.subSize || "medium";
  }
});
</script>
