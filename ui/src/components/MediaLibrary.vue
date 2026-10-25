<template>
  <div class="vcard" style="margin-top:10px">
    <div class="vh" style="display:flex;align-items:center;justify-content:space-between">
      <span style="display:flex;align-items:center;gap:8px">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7V5a2 2 0 0 1 2-2h2"/><path d="M17 3h2a2 2 0 0 1 2 2v2"/><path d="M21 17v2a2 2 0 0 1-2 2h-2"/><path d="M7 21H5a2 2 0 0 1-2-2v-2"/><rect x="7" y="7" width="10" height="10" rx="1"/></svg>
        媒体库管理 <span class="hint">清理累积的图片 / 语音 / BGM</span>
      </span>
      <button type="button" class="cb-addbtn" style="width:auto;padding:6px 12px" :disabled="busy" @click="refresh">↻ 刷新</button>
    </div>

    <div v-if="busy" class="status" style="margin-top:6px">载入中…</div>

    <template v-else>
      <!-- 图片库 -->
      <div class="mlib">
        <div class="mlib-h">
          <span>🖼 图片库 <b class="cnt">{{ images.length }}</b><span class="hint"> · {{ fmtSize(totalImg) }}</span></span>
        </div>
        <div v-if="!images.length" class="mlib-empty">（图片库为空）</div>
        <div v-for="im in images" :key="'img-' + im.name" class="mlib-row">
          <img class="mlib-th" :src="baseUrl + '/images/preview?name=' + encodeURIComponent(im.name)" alt="" @error="($e.target as HTMLImageElement).style.visibility='hidden'">
          <span class="mlib-name" :title="im.name">{{ im.name }}</span>
          <span class="mlib-size">{{ fmtSize(im.size) }}</span>
          <button type="button" class="mlib-del" title="删除" @click="delImage(im.name)">✕</button>
        </div>
      </div>

      <!-- 语音库 -->
      <div class="mlib">
        <div class="mlib-h">
          <span>🎙 语音库 <b class="cnt">{{ voices.length }}</b><span class="hint"> · {{ fmtSize(totalVoice) }}</span></span>
        </div>
        <div v-if="!voices.length" class="mlib-empty">（语音库为空）</div>
        <div v-for="v in voices" :key="'voice-' + v.name" class="mlib-row">
          <span class="mlib-ico">🎙</span>
          <span class="mlib-name" :title="v.name">{{ v.name }}</span>
          <span class="mlib-size">{{ v.duration_s ? v.duration_s + "s · " : "" }}{{ fmtSize(v.size) }}</span>
          <button type="button" class="mlib-del" title="删除" @click="delVoice(v.name)">✕</button>
        </div>
      </div>

      <!-- BGM 库 -->
      <div class="mlib">
        <div class="mlib-h">
          <span>🎵 BGM 曲库 <b class="cnt">{{ music.length }}</b><span class="hint"> · {{ fmtSize(totalMusic) }}</span></span>
        </div>
        <div v-if="!music.length" class="mlib-empty">（BGM 曲库为空）</div>
        <div v-for="m in music" :key="'mus-' + m.name" class="mlib-row">
          <span class="mlib-ico">🎵</span>
          <span class="mlib-name" :title="m.name">{{ m.name }}</span>
          <span class="mlib-size">{{ m.duration_s ? m.duration_s + "s · " : "" }}{{ fmtSize(m.size) }}</span>
          <button type="button" class="mlib-del" title="删除" @click="delMusic(m.name)">✕</button>
        </div>
      </div>

      <div class="status" style="margin-top:8px;min-height:14px">{{ status }}</div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { api, baseUrl, type ImageItem, type VoiceItem, type MusicItem } from "../api";
import { useAppStore } from "../store";
import { uiConfirm } from "../dialogs";

const store = useAppStore();
const busy = ref(true);
const status = ref("");
const images = ref<ImageItem[]>([]);
const voices = ref<VoiceItem[]>([]);
const music = ref<MusicItem[]>([]);

const totalImg = computed(() => images.value.reduce((s, x) => s + (x.size || 0), 0));
const totalVoice = computed(() => voices.value.reduce((s, x) => s + (x.size || 0), 0));
const totalMusic = computed(() => music.value.reduce((s, x) => s + (x.size || 0), 0));

function fmtSize(b?: number) {
  if (!b || b <= 0) return "0 B";
  const u = ["B", "KB", "MB", "GB"];
  let i = 0;
  while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
  return b.toFixed(b < 10 && i > 0 ? 1 : 0) + " " + u[i];
}

async function refresh() {
  busy.value = true;
  status.value = "";
  try {
    const [a, b, c] = await Promise.all([
      api.listImages(),
      api.voices(),
      api.music(),
    ]);
    images.value = a.images || [];
    voices.value = b.voices || [];
    music.value = c.music || [];
  } catch (ex: any) {
    status.value = "✕ 载入媒体库失败：" + (ex?.message || ex);
  } finally {
    busy.value = false;
  }
}

async function delImage(name: string) {
  if (!(await uiConfirm("删除图片库中的 " + name + "？\n注意：若某项目/实体正引用它，合成时将提示图片缺失。"))) return;
  try {
    const j = await api.deleteImage(name);
    if (j.ok) {
      images.value = images.value.filter(x => x.name !== name);
      status.value = "✅ 已删除图片 " + name;
    } else status.value = "✕ " + (j.detail || "删除失败");
  } catch (ex: any) {
    status.value = "✕ 删除失败：" + (ex?.message || ex);
  }
}
async function delVoice(name: string) {
  if (!(await uiConfirm("删除语音库中的 " + name + "？"))) return;
  try {
    const j = await api.deleteVoice(name);
    if (j.ok) {
      voices.value = voices.value.filter(x => x.name !== name);
      await store.loadVoices(); // 同步其它组件（声源选择器）
      status.value = "✅ 已删除语音 " + name;
    } else status.value = "✕ " + (j.detail || "删除失败");
  } catch (ex: any) {
    status.value = "✕ 删除失败：" + (ex?.message || ex);
  }
}
async function delMusic(name: string) {
  if (!(await uiConfirm("删除 BGM 曲库中的 " + name + "？"))) return;
  try {
    const j = await api.deleteMusic(name);
    if (j.ok) {
      music.value = music.value.filter(x => x.name !== name);
      await store.loadMusic(); // 同步 BgmExportCard 的曲库选择器
      status.value = "✅ 已删除 BGM " + name;
    } else status.value = "✕ " + (j.detail || "删除失败");
  } catch (ex: any) {
    status.value = "✕ 删除失败：" + (ex?.message || ex);
  }
}

onMounted(refresh);
</script>

<style scoped>
.mlib { margin-top:12px; }
.mlib-h { font-size:13px; color:var(--fg2); margin-bottom:6px; }
.mlib-h .cnt { color:var(--fg); margin:0 4px; }
.mlib-row { display:flex; align-items:center; gap:10px; padding:5px 8px; border:1px solid var(--border); border-radius:7px; margin-top:6px; background:var(--panel); }
.mlib-th { width:44px; height:44px; object-fit:cover; border-radius:6px; background:#000; flex:0 0 auto; }
.mlib-ico { font-size:18px; width:44px; text-align:center; flex:0 0 auto; }
.mlib-name { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:13px; color:var(--fg); }
.mlib-size { flex:0 0 auto; font-size:11.5px; color:var(--dim2); min-width:64px; text-align:right; }
.mlib-del { flex:0 0 auto; width:26px; height:26px; line-height:24px; padding:0; border:1px solid var(--border); background:transparent; color:var(--dim); border-radius:6px; cursor:pointer; font-size:13px; }
.mlib-del:hover { border-color:#c0392b; color:#e74c3c; background:rgba(192,57,43,.12); }
.mlib-empty { font-size:12.5px; color:var(--dim2); padding:4px 2px; }
</style>
