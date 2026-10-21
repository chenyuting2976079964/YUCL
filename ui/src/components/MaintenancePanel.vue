<template>
  <div class="pane" style="margin-bottom:14px">
    <details>
      <summary style="cursor:pointer;list-style:none">
        <span class="cb-sec-h">维护 <span class="hint" style="font-weight:400">备份 / 恢复 / 日志 / 手册</span></span>
      </summary>
      <div style="margin-top:8px;display:flex;flex-direction:column;gap:8px">
        <button type="button" class="cb-addbtn" style="width:100%;padding:8px 14px" @click="backup">⬇ 下载备份（图片 / 语音 / BGM / 任务历史 / 模型配置）</button>
        <button v-if="showBkFallback" type="button" class="cb-addbtn" style="width:100%;padding:8px 14px" @click="backupInBrowser">⇕ 用系统浏览器下载备份（窗口内保存失败时用）</button>
        <div class="upl">
          <input type="file" ref="rf" accept=".zip" @change="restore">
          <span>恢复备份（选择备份 zip，覆盖本机数据）</span>
        </div>
        <button type="button" class="cb-addbtn" style="width:100%;padding:8px 14px" @click="viewLogs">查看日志（最近 200 行）</button>
        <div v-if="showLogs">
          <pre style="max-height:260px;overflow:auto;font-size:11.5px;background:var(--panel);border:1px solid var(--border);border-radius:7px;padding:8px;margin:6px 0 0;white-space:pre-wrap;word-break:break-all">{{ logText }}</pre>
          <button type="button" class="cb-addbtn" style="width:auto;padding:8px 14px;margin-top:6px" @click="copyLogs">复制日志</button>
        </div>
        <a class="cb-addbtn" style="display:block;width:100%;padding:8px 14px;text-align:center" href="manual.html" target="_blank">📖 用户手册 ↗</a>
        <div class="status">{{ status }}</div>
      </div>
    </details>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { api } from "../api";
import { useAppStore } from "../store";
import { uiConfirm, uiPrompt } from "../dialogs";

const store = useAppStore();
const status = ref("");
const showLogs = ref(false);
const logText = ref("");
const showBkFallback = ref(false);
const rf = ref<HTMLInputElement | null>(null);

async function backup() {
  status.value = "打包备份中（图片/语音/BGM/历史/配置可能较大，稍候）…";
  try {
    const resp = await api.backup();
    if (!resp.ok) throw new Error("HTTP " + resp.status);
    const blob = await resp.blob();
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "ai-video-backup-" + new Date().toISOString().slice(0, 16).replace(/[:T]/g, "") + ".zip";
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
    // JavaFX 窗口（WebView2）默认没有下载目录，blob 下载可能静默失败 → 兜底走系统浏览器
    status.value = "✅ 备份已开始下载（" + (blob.size / 1048576).toFixed(1) + " MB）· 若窗口内没有保存成功，点「用系统浏览器下载备份」";
    showBkFallback.value = true;
  } catch (e: any) {
    status.value = "✕ 备份失败：" + (e?.message || e);
  }
}
async function backupInBrowser() {
  // 窗口内下载失败时的兜底：让后端在系统浏览器里打开 /backup（Chromium 浏览器可正常存盘）
  try {
    const j = await api.openInBrowser(location.origin + "/api/plugins/ai-video/backup");
    status.value = j?.ok ? "✅ 已在系统浏览器开始下载备份（存到浏览器下载目录）" : "✕ 打开系统浏览器失败";
  } catch {
    status.value = "✕ 打开系统浏览器失败";
  }
}
async function restore(e: Event) {
  const input = e.target as HTMLInputElement;
  const f = input.files?.[0];
  if (!f) return;
  if (!(await uiConfirm("从备份 " + f.name + " 恢复？将覆盖本机的 图片库/语音库/BGM/任务历史/模型配置。"))) {
    input.value = "";
    return;
  }
  status.value = "恢复中 " + f.name + " …";
  const fd = new FormData();
  fd.append("file", f);
  try {
    const j = await api.restore(fd);
    status.value = j.ok
      ? "✅ 已恢复 " + j.restored + " 个文件" + (j.skipped && j.skipped.length ? "（跳过：" + j.skipped.join("；") + "）" : "")
      : "✕ " + (j.detail || j.message || j.error || "恢复失败");
    if (j.ok) {
      await store.loadMusic();
      await store.loadVoices();
      // 恢复还包含 jobs.json / provider-config.json → 刷新近期作品与模型选项，避免界面继续显示旧数据
      store.loadJobs();
      store.loadOptions();
    }
  } catch (ex: any) {
    status.value = "✕ 恢复失败：" + (ex?.message || ex);
  }
  input.value = "";
}
async function viewLogs() {
  try {
    const j = await api.logs(200);
    showLogs.value = true;
    logText.value = j.ok
      ? (j.lines && j.lines.length ? j.lines.join("\n") : "（app.log 还不存在——服务刚启动过才会写入）")
      : "（读取失败：" + (j.detail || "未知错误") + "）";
    status.value = j.ok && j.lines && j.lines.length ? "已载入最近 " + j.lines.length + " 行" : "";
  } catch (ex: any) {
    showLogs.value = true;
    logText.value = "（读取失败：" + (ex?.message || ex) + "）";
    status.value = "";
  }
}
function copyLogs() {
  const txt = logText.value;
  (navigator.clipboard ? navigator.clipboard.writeText(txt) : Promise.reject()).then(
    () => { status.value = "已复制到剪贴板"; },
    () => { uiPrompt("复制失败，请手动选择复制：", txt); }
  );
}
</script>
