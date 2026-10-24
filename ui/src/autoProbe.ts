// 静默自动探测：窗口（WebEngine WebKit）到底能解什么编码。
// 应用启动后自动跑一次（无需用户点"窗口播放器诊断"）：
//   1. 已探测过（play-codec.json 存在）→ 跳过
//   2. 否则取一个最近完成的作品，请后端生成各编码变体，用隐藏 <video> 逐个试播
//   3. 能播的写入 /playtest/result（窗口版自动跟着该编码转码）；
//      全都播不了 → 写 "none"（窗口版不再转码，播放走原生 ffmpeg 播放器）
import { baseUrl } from "./api";

let started = false;

async function probeOne(url: string, timeoutMs: number): Promise<boolean> {
  return new Promise((resolve) => {
    const el = document.createElement("video");
    el.muted = true;
    el.preload = "auto";
    el.style.display = "none";
    document.body.appendChild(el);
    let settled = false;
    let startAt = -1;
    const timer = setTimeout(() => finish(false), timeoutMs);
    function finish(ok: boolean) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      el.remove();
      resolve(ok);
    }
    el.addEventListener("loadeddata", () => {
      startAt = performance.now();
      // 主动 play() 推引擎（headless/受限环境下光 loadeddata 不一定能解码）
      el.play().catch(() => {});
    });
    el.addEventListener("playing", () => {
      if (startAt > 0 && el.currentTime > 0.05 && performance.now() - startAt < timeoutMs) finish(true);
    });
    el.addEventListener("error", () => finish(false));
    // 兜底：6s 后还没 playing 但已加载 → 再看 currentTime 是否推进
    setTimeout(() => {
      if (!settled && el.currentTime > 0.05) finish(true);
    }, Math.max(3000, timeoutMs - 1000));
    el.src = url;
  });
}

export async function autoProbeCodec(jobId: string | undefined): Promise<void> {
  if (started || !jobId) return;
  started = true;
  try {
    const st = await (await fetch(baseUrl + "/playtest/result")).json();
    if (st && st.probe_set) return; // 探测过（含手动诊断结果）→ 不重复
    const v = await (
      await fetch(baseUrl + "/playtest/variants", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ job_id: jobId }),
      })
    ).json();
    if (!v || !v.ok || !Array.isArray(v.variants)) return;
    for (const item of v.variants) {
      if (!item.ready || !item.url) continue;
      const playable = await probeOne(baseUrl + item.url, 6000);
      if (playable) {
        await fetch(baseUrl + "/playtest/result", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ codec: item.codec }),
        });
        return;
      }
    }
    // 全部解不了 → 标记 none：窗口版不再转码，播放走原生播放器
    await fetch(baseUrl + "/playtest/result", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ codec: "none" }),
    });
  } catch {
    // 静默：探测失败不影响主流程（原生播放器始终是兜底）
  }
}
