import { defineStore } from "pinia";
import { api, type Job, type MusicItem, type Options, type Provider, type VoiceItem } from "./api";

// 全局应用状态：选项 / 供应商 / 语料库 / 任务
export const useAppStore = defineStore("app", {
  state: () => ({
    options: null as Options | null,
    backendOk: true,
    backendWarn: "",
    voices: [] as VoiceItem[],
    music: [] as MusicItem[],
    providers: [] as Provider[],
    activeProvider: "",
    providerTest: {} as Record<number, string>,
    jobs: [] as Job[],
    runningPoll: false
  }),
  getters: {
    activeProviderObj(state): Provider | undefined {
      return state.providers.find(p => p.id === state.activeProvider);
    }
  },
  actions: {
    async loadOptions() {
      try {
        const o = await api.options();
        this.options = o;
        this.backendOk = true;
        this.backendWarn = "";
        this.providers = Array.isArray(o.providers) ? o.providers : [];
        this.activeProvider = o.active_provider_id || "";
      } catch (e) {
        this.backendOk = false;
        this.backendWarn = "⚠ 连不上本地后端，请确认独立窗口服务已启动（见启动控制台）。";
        throw e;
      }
    },
    async loadVoices() {
      try {
        const j = await api.voices();
        this.voices = j.voices || [];
      } catch { /* 静默 */ }
    },
    async loadMusic() {
      try {
        const j = await api.music();
        this.music = j.music || [];
      } catch { /* 静默 */ }
    },
    async loadProviders() {
      try {
        const j = await api.providerConfig();
        if (j && Array.isArray(j.providers)) {
          this.providers = j.providers;
          this.activeProvider = String(j.active || "");
        }
      } catch { /* 静默 */ }
    },
    async loadJobs() {
      let fetchOk = true;
      try {
        const j = await api.recent();
        if (j && Array.isArray(j.jobs)) {
          this.jobs = j.jobs;
        } else {
          // 后端异常（非 2xx/结构化错误）时保留旧列表，避免任务面板凭空清空
          fetchOk = false;
        }
      } catch { /* 网络异常：保留旧列表，静默 */ fetchOk = false; }
      if (!fetchOk) {
        return;
      }
      const hasRunning = this.jobs.some(x => {
        const st = x.status || "done";
        return st === "running" || st === "queued"; // 排队的任务同样需要轮询（前面任务渲染期间它一直 queued）
      });
      if (hasRunning && !this.runningPoll) {
        this.runningPoll = true;
        const tick = async () => {
          try {
            await this.loadJobs();
            if (this.jobs.some(x => { const st = x.status || "done"; return st === "running" || st === "queued"; })) {
              setTimeout(tick, 2000);
            } else {
              this.runningPoll = false;
            }
          } catch { /* 静默 */ }
        };
        setTimeout(tick, 2000);
      }
    }
  }
});
