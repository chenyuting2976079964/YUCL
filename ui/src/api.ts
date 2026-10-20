// YUCL API 客户端：与 Spring Boot 后端（/api/plugins/ai-video）的完整类型化接口
export const API = "/api/plugins/ai-video";
export const baseUrl = API;

export interface ProviderModel {
  image_models: string[];
  video_models: string[];
  text_models: string[];
  default_image_model?: string;
  default_video_model?: string;
  default_text_model?: string;
}

export interface Provider extends ProviderModel {
  id: string;
  name: string;
  builtin?: boolean;
  base_url?: string;
  api_key?: string;
  has_key?: boolean;
}

export interface Options {
  orientations: Array<{ key: string; label?: string; title?: string }>;
  voices: Array<{ value: string; label: string }>;
  default_voice?: string;
  video_models: string[];
  image_models: string[];
  text_models: string[];
  default_video_model?: string;
  default_image_model?: string;
  default_text_model?: string;
  providers: Provider[];
  active_provider_id?: string;
}

export interface ScriptDoc {
  title?: string;
  logline?: string;
  characters: Array<{ name: string; appearance?: string; voice?: string }>;
  scenes: Array<{ background?: string; shot?: string; dialogue?: string; seconds?: number; character?: string }>;
}

export interface ProviderStatus { ok: boolean; provider?: string; reason?: string }

export interface JobProgress { percent?: number; message?: string }

export interface Job {
  id: string;
  topic?: string;
  status?: string; // running | done | failed | cancelled
  progress?: JobProgress;
  error?: string;
  duration_s?: number;
  style?: string;
  voice_warnings?: string[];
}

export interface VoiceItem { name: string; path?: string; duration_s?: number }
export interface MusicItem { name: string; path?: string; duration_s?: number }

export interface CharBgShotPayload {
  character: string;
  character2: string;
  background: string;
  text: string;
  seconds: number;
  engine: string;
}

export interface MakeCharBgBody {
  characters: Array<{ name: string; image: string; url: string; source: string }>;
  backgrounds: Array<{ name: string; image: string; url: string; source: string }>;
  shots: CharBgShotPayload[];
  provider_id?: string;
  orientation: string;
  model: string;
  char_scale: number;
  char_pos: string;
  subtitles: boolean;
  narration: boolean;
  default_voice?: string;
  local_audio?: { _default: string } | null;
  char_voices: Record<string, string>;
  bgm: string;
  bgm_vol: number;
  quality: string;
  sub_size: string;
}

export interface MediaMeta {
  duration_s?: number;
  size?: string;
  subtitles?: boolean;
  model?: string;
  audio_sources?: Record<string, number>;
}

async function j<T>(resp: Response): Promise<T> {
  return (await resp.json()) as T;
}

export const api = {
  options: () => fetch(API + "/options", { cache: "no-store" }).then(r => j<Options>(r)),
  agnesaStatus: () => fetch(API + "/agnesa-status").then(r => j<ProviderStatus & { ok: boolean }>(r)),

  script: (b: { topic: string; style: string; shots: number; extra: string; model: string; provider_id: string }) =>
    fetch(API + "/script", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(b) })
      .then(r => j<{ ok: boolean; script?: ScriptDoc; raw?: string; provider?: string; model?: string; error?: string; detail?: string }>(r)),

  genImage: (b: { prompt: string; size: string; model: string; provider_id: string }, signal?: AbortSignal) =>
    fetch(API + "/agnes-image", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify(b), signal
    }).then(r => j<{ ok: boolean; local_path?: string; url?: string; error?: string; detail?: string }>(r)),

  uploadImage: (fd: FormData) =>
    fetch(API + "/images/upload", { method: "POST", body: fd })
      .then(r => j<{ ok: boolean; name?: string; path?: string; detail?: string; error?: string }>(r)),

  makeCharBg: (body: MakeCharBgBody) =>
    fetch(API + "/make-char-bg", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) })
      .then(r => j<{ ok: boolean; job_id?: string; error?: string; detail?: string }>(r)),

  recent: () => fetch(API + "/recent").then(r => j<{ jobs: Job[] }>(r)),
  cancelJob: (id: string) =>
    fetch(API + "/jobs/" + encodeURIComponent(id) + "/cancel", { method: "POST" }).then(r => j<{ ok: boolean; error?: string; detail?: string }>(r)),
  retryJob: (id: string) =>
    fetch(API + "/jobs/" + encodeURIComponent(id) + "/retry", { method: "POST" }).then(r => j<{ ok: boolean; error?: string; detail?: string }>(r)),
  deleteJob: (id: string) =>
    fetch(API + "/jobs/" + encodeURIComponent(id), { method: "DELETE" }).then(r => j<{ ok: boolean; error?: string; detail?: string }>(r)),
  openInBrowser: (url: string) =>
    fetch(API + "/open-in-browser?url=" + encodeURIComponent(url)).then(r => j<{ ok: boolean }>(r)),

  voices: () => fetch(API + "/voices").then(r => j<{ voices: VoiceItem[] }>(r)),
  uploadVoice: (fd: FormData) =>
    fetch(API + "/voices/upload", { method: "POST", body: fd })
      .then(r => j<{ ok: boolean; voice?: VoiceItem; detail?: string; error?: string }>(r)),

  music: () => fetch(API + "/music").then(r => j<{ music: MusicItem[] }>(r)),
  uploadMusic: (fd: FormData) =>
    fetch(API + "/music/upload", { method: "POST", body: fd })
      .then(r => j<{ ok: boolean; music?: MusicItem; detail?: string; error?: string }>(r)),
  deleteMusic: (name: string) =>
    fetch(API + "/music/" + encodeURIComponent(name), { method: "DELETE" })
      .then(r => j<{ ok: boolean; detail?: string }>(r)),

  providerConfig: () =>
    fetch(API + "/provider-config").then(r => j<{ active?: string; providers?: Provider[] }>(r)),
  saveProviderConfig: (c: { active: string; providers: Provider[] }) =>
    fetch(API + "/provider-config", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(c) })
      .then(r => j<{ ok: boolean; count?: number; active?: string; error?: string; detail?: string }>(r)),
  testProvider: (p: Provider) =>
    fetch(API + "/provider-test", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(p) })
      .then(r => j<{ ok: boolean; hint?: string; models?: string[]; error?: string; detail?: string }>(r)),

  backup: () => fetch(API + "/backup"),
  restore: (fd: FormData) =>
    fetch(API + "/restore", { method: "POST", body: fd })
      .then(r => j<{ ok: boolean; restored?: number; skipped?: string[]; detail?: string; message?: string; error?: string }>(r)),
  logs: (lines = 200) =>
    fetch(API + "/logs?lines=" + lines).then(r => j<{ ok: boolean; lines?: string[]; detail?: string }>(r))
};
