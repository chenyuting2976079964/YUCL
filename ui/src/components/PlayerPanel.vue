<template>
  <div>
    <div class="sect">
      <h2>播放预览</h2>
      <div class="player" :class="{ 'has-video': !!current }">
        <div v-if="!current" class="ph">
          <div class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="6 3 20 12 6 21 6 3"/></svg></div>
          生成后在此播放 · 或从下方选择历史作品
        </div>
        <video ref="vid" v-show="current" controls style="max-height:64vh"></video>
        <div class="meta">
          <button v-if="current" type="button" id="player-open" class="chipbtn" title="窗口内播放器不支持 h264 时使用" @click="openInBrowser">⇕&emsp;在系统浏览器打开</button>
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
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
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

const audioSrcs = computed(() => {
  const a = meta.value?.audio_sources;
  if (!a) return "";
  return Object.keys(a).filter(x => a[x] > 0).join(" / ");
});

function onStreamError() {
  if (vid.value?.error) {
    playerHint.value = "⚠ 窗口内播放器无法播放此视频（编码不受支持）——点右侧按钮用系统浏览器打开。";
  }
}
function onSelect(jobId: string, m?: unknown) {
  activeJobId.value = jobId;
  playerError.value = "";
  meta.value = null;
  const streamUri = baseUrl + "/stream?job_id=" + encodeURIComponent(jobId);
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

onMounted(() => {
  vid.value?.addEventListener("error", onStreamError);
  store.loadJobs();
});
</script>
