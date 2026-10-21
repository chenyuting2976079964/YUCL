import { reactive } from "vue";

// ── 轻量 i18n：语言偏好持久化在 localStorage，供设置弹窗切换 ──
export type Lang = "zh" | "en";

const DICT: Record<Lang, Record<string, string>> = {
  zh: {
    app_sub: "AI 短视频工作室 · 人物·背景成片 · 本地免费 · 独立窗口运行",
    settings: "设置",
    st_settings: "设置",
    st_language: "语言",
    st_appearance: "外观",
    st_model: "模型配置",
    st_close: "关闭",
    st_lang_desc: "界面显示语言（即时生效，仅影响界面文字）",
    st_ap_desc: "主题色 / 字体大小 / 窗口密度",
    st_model_desc: "模型供应商与 API Key（本地保存，不上传）",
    st_accent: "主题色",
    st_font: "字号",
    st_font_std: "标准",
    st_font_large: "大字号",
    st_density: "窗口密度",
    st_density_comfort: "舒适（默认）",
    st_density_compact: "紧凑",
    st_window: "窗口",
    st_reset_size: "恢复默认窗口大小",
    step1: "人物图片",
    step2: "场景图片",
    step3: "内容描述",
    step4: "生成视频",
    step1_hint: "有图直接上传；没有就用提示词让生图模型生成",
    step2_hint: "同上：上传或提示词生成",
    step3_hint: "每镜一个描述 / 旁白文本，可多镜",
    step4_hint: "画幅 · 模型 · 字幕 · 配音 · BGM，一键出片",
    gen_video: "生成人物·背景视频",
    start: "开始",
    add_char: "＋ 添加人物",
    add_bg: "＋ 添加背景",
    add_shot: "＋ 添加镜头",
    del: "删除",
    upload_img: "上传图片",
    prompt: "提示词",
    regenerate: "重新生成",
    generating: "生成中…",
    name_ph: "名称",
    desc_ph: "描述这张图，例：蓝发女孩站在滑板公园",
    select_file: "选择本地图片文件（≤50MB）",
    selected: "已选",
    shots: "镜头",
    shots_hint: "每镜 1–2 个人物 + 1 个背景",
    chars_sec: "人物图片",
    bgs_sec: "场景图片",
    char1: "人物 1",
    char2: "人物 2（可选）",
    bg_label: "背景",
    none: "— 无 —",
    duration: "时长（秒）",
    action_ph: "动作 / 旁白",
    engine_local: "本地合成（离线）",
    engine_ai: "Agnes AI 动态",
    del_shot: "删除镜头",
    orientation: "画幅",
    provider: "模型供应商",
    video_model: "视频模型",
    img_model: "生图模型",
    char_scale: "人物大小",
    char_pos: "单人站位",
    pos_bl: "左下", pos_bc: "中下", pos_br: "右下", pos_c: "居中",
    subtitles: "烧录字幕",
    narration: "旁白配音（人物卡可各设音源；关=静默）",
    starting: "启动中…",
    status_ok: "✅ 任务已开始",
    progress_hint: "进度 / 取消 / 重试见「近期作品」"
  },
  en: {
    app_sub: "AI Short-Video Studio · Char/BG composition · free on-device · standalone window",
    settings: "Settings",
    st_settings: "Settings",
    st_language: "Language",
    st_appearance: "Appearance",
    st_model: "Model Providers",
    st_close: "Close",
    st_lang_desc: "UI display language (instant, UI text only)",
    st_ap_desc: "Accent color / font size / window density",
    st_model_desc: "Model providers & API keys (stored locally only)",
    st_accent: "Accent color",
    st_font: "Font size",
    st_font_std: "Standard",
    st_font_large: "Large",
    st_density: "Window density",
    st_density_comfort: "Comfortable (default)",
    st_density_compact: "Compact",
    st_window: "Window",
    st_reset_size: "Reset window size",
    step1: "Character Image",
    step2: "Scene Image",
    step3: "Content Description",
    step4: "Generate Video",
    step1_hint: "Upload if you have one; otherwise let the image model generate it",
    step2_hint: "Same: upload or generate from a prompt",
    step3_hint: "One description / narration per shot, multiple shots allowed",
    step4_hint: "Aspect · model · subs · TTS · BGM — one-click render",
    gen_video: "Generate Video",
    start: "Start",
    add_char: "+ Add Character",
    add_bg: "+ Add Scene",
    add_shot: "+ Add Shot",
    del: "Delete",
    upload_img: "Upload Image",
    prompt: "Prompt",
    regenerate: "Regenerate",
    generating: "Generating…",
    name_ph: "Name",
    desc_ph: "Describe the image, e.g. blue-haired girl at a skate park",
    select_file: "Pick a local image file (≤50MB)",
    selected: "Selected",
    shots: "Shots",
    shots_hint: "Each shot: 1–2 characters + 1 scene",
    chars_sec: "Character Images",
    bgs_sec: "Scene Images",
    char1: "Character 1",
    char2: "Character 2 (optional)",
    bg_label: "Scene",
    none: "— None —",
    duration: "Seconds",
    action_ph: "Action / narration",
    engine_local: "Local composite (offline)",
    engine_ai: "Agnes AI motion",
    del_shot: "Delete shot",
    orientation: "Aspect",
    provider: "Provider",
    video_model: "Video model",
    img_model: "Image model",
    char_scale: "Character size",
    char_pos: "Placement",
    pos_bl: "Bottom-left", pos_bc: "Bottom-center", pos_br: "Bottom-right", pos_c: "Center",
    subtitles: "Burn-in subtitles",
    narration: "TTS narration (per-character source; off = silent)",
    starting: "Starting…",
    status_ok: "✅ Job started",
    progress_hint: "Progress / cancel / retry in Recent Works"
  }
};

export const i18n = reactive({ lang: (localStorage.getItem("yucl_lang") as Lang) || "zh" });

export function t(key: string): string {
  const d = DICT[i18n.lang];
  return (d && d[key]) || DICT.zh[key] || key;
}

export function setLang(l: Lang) {
  i18n.lang = l;
  localStorage.setItem("yucl_lang", l);
}

// ── 外观偏好（主题色 / 字号 / 密度）──
export interface Appearance { accent: string; font: "std" | "large"; density: "comfort" | "compact" }
const DEF: Appearance = { accent: "#6366f1", font: "std", density: "comfort" };
export const ACCENTS = [
  { c: "#6366f1", n: "靛蓝（默认）" },
  { c: "#4f7cff", n: "蓝" },
  { c: "#8b5cf6", n: "紫" },
  { c: "#10b981", n: "绿" },
  { c: "#f59e0b", n: "橙" },
  { c: "#ef4444", n: "红" }
];

export const appearance = reactive<Appearance>(loadAppear());

function loadAppear(): Appearance {
  try {
    const raw = localStorage.getItem("yucl_appearance");
    if (raw) return { ...DEF, ...JSON.parse(raw) };
  } catch { /* ignore */ }
  return { ...DEF };
}

export function applyAppearance(a: Appearance) {
  const r = document.documentElement;
  r.style.setProperty("--accent", a.accent);
  r.style.setProperty("font-size", a.font === "large" ? "15.5px" : "14px");
  r.dataset.density = a.density;
  localStorage.setItem("yucl_appearance", JSON.stringify(a));
}

export function resetWindowSize() {
  // 通知后端把窗口恢复默认 1220×780（物理像素）
  fetch("/api/plugins/ai-video/window/reset").catch(() => {});
}
