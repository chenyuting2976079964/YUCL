<template>
  <div>
    <div class="sect">
      <h2>播放预览</h2>
      <div class="player" :class="{ 'has-video': !!current }">
        <div v-if="!current" class="ph">
          <div class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="6 3 20 12 6 21 6 3"/></svg></div>
          生成后在此播放 · 或从下方选择历史作品
        </div>
        <video ref="vid" v-show="current" controls @click="onVidClick" style="max-height:64vh; cursor: default"></video>
        <div class="meta">
          <button v-if="current" type="button" id="player-native" class="chipbtn accent" title="内置网页黑屏？点我：随包 ffmpeg 直接解码渲染（H.264/HEVC/任何编码都行，原画无转码）" @click="requestNative(false)">⚡&emsp;原生播放器</button>
          <button v-if="current" type="button" id="player-open" class="chipbtn" title="窗口内播放异常、或想用完整功能（字幕轨道等）时，用系统浏览器打开" @click="openInBrowser">⇕&emsp;在系统浏览器打开</button>
          <span v-if="playerHint" class="phint">{{ playerHint }}</span>
          <template v-if="meta">
            <span class="chip"><span class="k">时长</span><b>{{ meta.duration_s ?? "-" }}s</b></span>
            <span class="chip"><span class="k">规格</span><b>{{ meta.size || "-" }}</b></span>
            <span class="chip"><span class="k">字幕</span><b>{{ meta.subtitles ? "有" : "无" }}</b></span>
            <span v-if="audioSrcs" class="chip"><span class="k">声源</span><b>{{ audioSrcs }}</b></span>
            <span v-if="meta.model" class="chip"><span class="k">模型</span><b>{{ meta.model }}</b></span>
          </template>
          <span v-else-if="playerError" class="chip"><b>{{ playerError }}</b></span>
        </div>
      </div>
    </div>
    <div class="sect">
      <h2>最近作品</h2>
      <div class="jobs">
        <JobCard v-for="x in jobs" :key="x.id" :job="x" :active="x.id === activeJobId"
          @select="onSelect" @cancel="cancel" @retry="retry" @delete="del" />
        <div v-if="!jobs.length" class="empty">暂无作品 · 生成一个试试吧</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { api, baseUrl, type Job, type MediaMeta } from "../api";
import { useAppStore } from "../store";
import { uiAlert, uiConfirm } from "../dialogs";
import JobCard from "./JobCard.vue";

const store = useAppStore();
const jobs = computed(() => store.jobs);
const activeJobId = ref("");
const current = ref("");
const meta = ref<MediaMeta | null>(null);
const playerHint = ref("");
const playerError = ref("");
const vid = ref<HTMLVideoElement | null>(null);
const nativeRequested = ref<Set<string>>(new Set());

const audioSrcs = computed(() => {
  const a = meta.value?.audio_sources;
  if (!a) return "";
  return Object.keys(a).filter(x => a[x] > 0).join(" / ");
});

function onStreamError() {
  if (vid.value?.error) {
    // WebEngine 的 WebKit 媒体栈解不了 H.264/MPEG-4 → 自动切原生播放器（每个作品只自动一次）
    if (activeJobId.value && !nativeRequested.value.has(activeJobId.value)) {
      nativeRequested.value.add(activeJobId.value);
      requestNative(true);
      return;
    }
    playerHint.value = "⚠ 窗口内网页播放器无法播放此视频（编码不受支持）——点「⚡ 原生播放器」或用系统浏览器打开。";
  }
}
// WebEngine 对 H.264 常常静默黑屏（不抛 JS error 事件）——3 秒还没解码成功就自动降级
let fallbackTimer = 0;
function armNativeFallback() {
  if (fallbackTimer) window.clearTimeout(fallbackTimer);
  fallbackTimer = window.setTimeout(() => {
    fallbackTimer = 0;
    const v = vid.value;
    const alive = v && v.readyState >= 2 && (!v.paused || v.currentTime > 0.05);
    if (!alive && activeJobId.value && !nativeRequested.value.has(activeJobId.value)) {
      nativeRequested.value.add(activeJobId.value);
      requestNative(true);
    }
  }, 3000);
}
async function requestNative(auto: boolean) {
  if (!activeJobId.value) return;
  try {
    const r = await fetch(baseUrl + "/playtest/native-request", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ job_id: activeJobId.value }),
    });
    const j = await r.json().catch(() => ({}));
    if (r.ok && j.ok) {
      playerHint.value = "已打开原生播放器。点 ✕ 关闭后，再点视频区或「⚡」可重新打开。";
    } else {
      playerHint.value = "⚠ 原生播放器启动失败：" + ((j && j.error) || "未知原因");
    }
  } catch (e: any) {
    playerHint.value = "⚠ 原生播放器启动失败：" + (e?.message || e);
  }
}
/** 点网页 <video>（黑屏/播不了的状态）→ 重新打开原生播放器。
 *  解决"×掉原生卡片后不知怎么再打开"：黑屏本身就是入口——
 *  只要该视频确认过 WebKit 播不了（error / 未解码成功 / 曾切过原生），点它就开卡片。 */
function onVidClick() {
  const v = vid.value;
  if (!v || !activeJobId.value) return;
  const broken = !!v.error || v.readyState < 2 || nativeRequested.value.has(activeJobId.value);
  if (broken) {
    requestNative(false);
  }
}
function onSelect(jobId: string, m?: unknown) {
  activeJobId.value = jobId;
  playerError.value = "";
  meta.value = null;
  const streamUri = baseUrl + "/stream?job_id=" + encodeURIComponent(jobId) + "&win=1"; // win=1：有"窗口版"（探测编码）时优先给窗口能播的版本
  playUri(streamUri, m as MediaMeta | undefined);
  // 拉完整 meta（字幕/模型/声源/规格）：卡片只带 duration_s，缺了会误显示「字幕: 无」
  api.jobDetail(jobId).then(d => {
    if (activeJobId.value !== jobId) return;
    meta.value = {
      duration_s: d.duration_s ?? ((m as MediaMeta | undefined)?.duration_s),
      size: d.size,
      subtitles: d.subtitles,
      model: d.model,
      audio_sources: d.audio_sources,
    };
  }).catch(() => {});
}
async function playUri(uri: string, m?: MediaMeta) {
  meta.value = m || null;
  armNativeFallback(); // 选片即挂 3s 保底：黑屏（无 error 事件）也能自动切原生播放器
  try {
    const r = await fetch(uri);
    if (r.ok) {
      current.value = uri;
      vid.value?.load();
      return;
    }
  } catch { /* fall through */ }
  try {
    const mm = await (await fetch(baseUrl + "/media?job_id=" + encodeURIComponent(uri.replace(baseUrl + "/stream?job_id=", "")))).json();
    if (mm && mm.dataUri) {
      current.value = mm.dataUri;
      return;
    }
  } catch { /* fall through */ }
  current.value = "";
  playerError.value = "该作品文件已不存在（或超过 40MB 直传上限）";
}
async function openInBrowser() {
  if (!current.value || !activeJobId.value) return;
  try {
    const j = await api.openJobInBrowser(activeJobId.value);
    if (j && j.ok) playerHint.value = "已在系统浏览器打开";
    else playerHint.value = "⚠ 打开失败：" + ((j && (j.error || j.detail)) || "未知原因");
  } catch (e) {
    playerHint.value = "⚠ 打开失败：" + ((e as Error)?.message || "网络错误");
  }
}
async function cancel(id: string) {
  let j: Awaited<ReturnType<typeof api.cancelJob>>;
  try {
    j = await api.cancelJob(id);
  } catch (e: any) {
    await uiAlert("取消失败：" + (e?.message || e));
    return;
  }
  if (j.ok) store.loadJobs();
  else await uiAlert("取消失败：" + (j.error || j.detail || "未知错误"));
}
async function retry(id: string) {
  let j: Awaited<ReturnType<typeof api.retryJob>>;
  try {
    j = await api.retryJob(id);
  } catch (e: any) {
    await uiAlert("重试失败：" + (e?.message || e));
    return;
  }
  if (j.ok) await store.loadJobs();
  else await uiAlert("重试失败：" + (j.error || j.detail || "未知错误"));
}
async function del(id: string) {
  if (!(await uiConfirm("删除该作品？\n会同时删除已生成的视频文件，不可恢复。"))) return;
  let j: Awaited<ReturnType<typeof api.deleteJob>>;
  try {
    j = await api.deleteJob(id);
  } catch (e: any) {
    await uiAlert("删除失败：" + (e?.message || e));
    return;
  }
  if (j.ok) {
    if (activeJobId.value === id) {
      activeJobId.value = "";
      current.value = "";
      meta.value = null;
    }
    await store.loadJobs();
  } else await uiAlert("删除失败：" + (j.error || j.detail || "未知错误"));
}

// ── 播放器框位置上报：原生播放器"框内播放"依赖 —— 持续把 <video> 元素的视口 rect
//    （= WebView root 坐标，CSS px 1:1）POST 给后端；浮层打开时嵌入该框，
//    滚动/缩放/切作品自动跟到新位置。未打开浮层时后端只缓存、不消耗。──
const rectState = { x: 0, y: 0, w: 0, h: 0, timer: 0, ro: null as ResizeObserver | null, lastSent: "" };
function reportPlayerRect() {
  const v = vid.value;
  if (!v || !current.value) return;
  const r = v.getBoundingClientRect();
  const x = Math.round(r.left), y = Math.round(r.top), w = Math.round(r.width), h = Math.round(r.height);
  const key = x + "," + y + "," + w + "," + h;
  if (key === rectState.lastSent) return; // 无变化不上报
  rectState.x = x; rectState.y = y; rectState.w = w; rectState.h = h;
  rectState.lastSent = key;
  if (rectState.timer) return; // 已在节流窗口内
  rectState.timer = window.setTimeout(() => {
    rectState.timer = 0;
    const s = JSON.stringify({ x: rectState.x, y: rectState.y, w: rectState.w, h: rectState.h });
    fetch(baseUrl + "/playtest/player-rect", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: s,
    }).catch(() => { /* 后端暂不可达，静默 */ });
  }, 250);
}
watch(current, (val) => {
  if (!val) {
    rectState.lastSent = "";
    return;
  }
  nextTick(() => {
    const v = vid.value;
    if (!v) return;
    if (!rectState.ro) {
      rectState.ro = new ResizeObserver(reportPlayerRect);
    }
    rectState.ro.disconnect();
    rectState.ro.observe(v);
    rectState.lastSent = ""; // 强制重新上报
    reportPlayerRect();
  });
});
onMounted(() => {
  document.addEventListener("scroll", reportPlayerRect, { capture: true, passive: true });
  window.addEventListener("resize", reportPlayerRect, { passive: true });
});
onBeforeUnmount(() => {
  document.removeEventListener("scroll", reportPlayerRect, { capture: true } as any);
  window.removeEventListener("resize", reportPlayerRect);
  rectState.ro?.disconnect();
  if (rectState.timer) window.clearTimeout(rectState.timer);
});

onMounted(() => {
  vid.value?.addEventListener("error", onStreamError);
  vid.value?.addEventListener("playing", () => {
    if (fallbackTimer) { window.clearTimeout(fallbackTimer); fallbackTimer = 0; } // 能播就不降级
  });
  store.loadJobs();
});
</script>
