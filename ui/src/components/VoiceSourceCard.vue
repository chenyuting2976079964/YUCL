<template>
  <div class="vcard">
    <div class="vh">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2M12 19v3"/></svg>
      旁白声源
    </div>
    <div class="seg">
      <label :class="{ on: mode === 'tts' }">
        <input type="radio" name="g-mode" value="tts" v-model="mode">系统音色（TTS）
      </label>
      <label :class="{ on: mode === 'file' }">
        <input type="radio" name="g-mode" value="file" v-model="mode">我的语音文件
      </label>
    </div>
    <div v-show="mode === 'tts'">
      <div class="field" style="margin-bottom:0">
        <label class="lbl">系统音色 <span class="hint">edge-tts，网络不可用时自动降级</span></label>
        <select v-model="voice">
          <option value="">— 请选择音色（{{ options?.voices?.length ? "系统音色加载完成" : "加载失败" }}）</option>
          <option v-for="v in options?.voices || []" :key="v.value" :value="v.value">{{ v.label || v.value }} · {{ v.value }}</option>
        </select>
      </div>
    </div>
    <div v-show="mode === 'file'">
      <div class="field">
        <label class="lbl">语音文件库 <span class="hint">选一个作为默认旁白</span></label>
        <select v-model="libName">
          <option value="">— 选择语音文件 —</option>
          <option v-for="v in store.voices" :key="v.name" :value="v.name">{{ v.name }} · {{ v.duration_s ?? 0 }}s</option>
        </select>
      </div>
      <div class="field">
        <label class="lbl">试听</label>
        <audio ref="prev" controls></audio>
      </div>
      <div class="upl">
        <input type="file" ref="up" accept="audio/*,.mp3,.wav,.m4a,.aac,.flac,.ogg,.wma,.opus" @change="upload">
        <span>上传到语音库（≤50MB）</span>
      </div>
      <div id="v-up-status" class="status" style="margin-top:6px">{{ upStatus }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { api, baseUrl } from "../api";
import { useAppStore } from "../store";

const store = useAppStore();
const options = computed(() => store.options);
const mode = ref<"tts" | "file">("tts");
const voice = ref(store.options?.default_voice || "");
// options 是异步加载的：setup 时若还没就绪，等它到达后回填默认音色（不覆盖用户手动选择）
watch(() => store.options, o => {
  if (!voice.value && o?.default_voice) voice.value = o.default_voice;
}, { immediate: true });
const libName = ref("");
const upStatus = ref("");
const prev = ref<HTMLAudioElement | null>(null);
const up = ref<HTMLInputElement | null>(null);

watch(libName, name => previewVoice(name));

function voiceLibPath(name: string) {
  return store.voices.find(o => o.name === name)?.path || "";
}
function previewVoice(name: string) {
  const a = prev.value;
  if (!a) return;
  if (!name) { a.removeAttribute("src"); a.pause(); return; }
  a.src = baseUrl + "/voices/preview?name=" + encodeURIComponent(name);
}
async function upload(e: Event) {
  const input = e.target as HTMLInputElement;
  const f = input.files?.[0];
  if (!f) return;
  upStatus.value = "上传中…";
  try {
    const fd = new FormData();
    fd.append("file", f, f.name);
    const r = await fetch(baseUrl + "/voices/upload", { method: "POST", body: fd });
    const j: any = await r.json().catch(() => ({}));
    if (r.ok && j.ok) {
      await store.loadVoices();
      const nm = j.voice.name;
      libName.value = nm;
      previewVoice(nm);
      upStatus.value = "✓ 已上传 " + nm;
    } else upStatus.value = "✕ " + (j.detail || j.error || ("HTTP " + r.status));
  } catch (ex: any) {
    upStatus.value = "✕ " + (ex?.message ? ex.message : ex);
  }
  input.value = "";
}

// 供父组件读取当前旁白负载
defineExpose({
  voicePayload() {
    const v = voice.value || "";
    let local_audio: { _default: string } | null = null;
    if (mode.value === "file") {
      const p = libName.value ? voiceLibPath(libName.value) : "";
      if (p) local_audio = { _default: p };
    }
    return { voice: v, local_audio };
  }
});
</script>
