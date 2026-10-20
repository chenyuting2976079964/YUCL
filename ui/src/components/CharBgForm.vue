<template>
  <form id="f-charbg" class="pane" @submit.prevent="submit">
    <div class="cb-sec-h">人物图片 <span class="hint" style="font-weight:400">每张可上传或提示词生成</span></div>
    <EntityCard v-for="e in chars" :key="e.id" :entity="e" kind="chars" :options="store.options"
      :voices="store.voices" :prov-id="provId" :img-model="imgModel" @removed="renderAll" />
    <button type="button" class="cb-addbtn" @click="addEntity('chars')">＋ 添加人物</button>

    <div class="cb-sec-h">背景图片 <span class="hint" style="font-weight:400">每张可上传或提示词生成</span></div>
    <EntityCard v-for="e in bgs" :key="e.id" :entity="e" kind="bgs" :options="store.options"
      :voices="store.voices" :prov-id="provId" :img-model="imgModel" @removed="renderAll" />
    <button type="button" class="cb-addbtn" @click="addEntity('bgs')">＋ 添加背景</button>

    <div class="cb-sec-h">镜头 <span class="hint" style="font-weight:400">每镜 1–2 个人物 + 1 个背景</span></div>
    <div v-for="(sh, i) in shots" :key="i" class="cb-shot" :data-i="i">
      <div class="srow">
        <select v-model="sh.char" title="人物 1">
          <option :value="0">— 无 —</option>
          <option v-for="c in chars" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <select v-model="sh.char2" title="人物 2（可选）">
          <option :value="0">— 无 —</option>
          <option v-for="c in chars" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
      </div>
      <div class="srow">
        <select v-model="sh.bg" title="背景">
          <option :value="0">— 无 —</option>
          <option v-for="b in bgs" :key="b.id" :value="b.id">{{ b.name }}</option>
        </select>
        <input v-model.number="sh.seconds" type="number" min="3" max="12" title="时长（秒）">
      </div>
      <div class="srow"><input class="full" v-model="sh.text" placeholder="动作 / 旁白"></div>
      <div class="srow">
        <select v-model="sh.engine">
          <option value="local">本地合成（离线）</option>
          <option value="ai">Agnes AI 动态</option>
        </select>
        <button type="button" class="cb-del" @click="shots.splice(i, 1)">删除镜头</button>
      </div>
    </div>
    <button type="button" class="cb-addbtn" @click="addShot">＋ 添加镜头</button>

    <div class="row" style="margin-top:12px">
      <div class="field">
        <label class="lbl">画幅</label>
        <select v-model="orientation">
          <option v-for="o in store.options?.orientations || []" :key="o.key" :value="o.key">{{ o.title || o.label || o.key }}</option>
        </select>
      </div>
      <div class="field">
        <label class="lbl" title="开放 harness：生成走哪家模型网关（下方「模型配置」可增删）">模型供应商</label>
        <select v-model="provId">
          <option v-for="p in store.providers" :key="p.id" :value="p.id">{{ provLabel(p) }}</option>
        </select>
      </div>
      <div class="field">
        <label class="lbl">视频模型 <span class="hint">ai 引擎</span></label>
        <select v-model="videoModel">
          <option v-for="m in videoModelList" :key="m" :value="m">{{ m }}</option>
        </select>
      </div>
      <div class="field">
        <label class="lbl">生图模型 <span class="hint">提示词卡</span></label>
        <select v-model="imgModel">
          <option v-for="m in imgModelList" :key="m" :value="m">{{ m }}</option>
        </select>
      </div>
    </div>
    <div class="row">
      <div class="field">
        <label class="lbl">人物大小</label>
        <input type="range" v-model.number="charScale" min="0.4" max="0.9" step="0.05">
        <div style="font-size:11px;color:var(--dim2);margin-top:4px">占画面高度比例 · 越大越近 <b style="color:var(--fg)">{{ charScale.toFixed(2) }}</b></div>
      </div>
      <div class="field">
        <label class="lbl">单人站位</label>
        <select v-model="charPos">
          <option value="bottom-left">左下</option>
          <option value="bottom-center">中下</option>
          <option value="bottom-right">右下</option>
          <option value="center">居中</option>
        </select>
        <div style="font-size:11px;color:var(--dim2);margin-top:4px">两人同框时自动左/右分置</div>
      </div>
    </div>
    <label class="check"><input type="checkbox" v-model="subtitles"> 烧录字幕</label>
    <label class="check"><input type="checkbox" v-model="narration"> 旁白配音（人物卡可各设音源；关=静默）</label>
    <button class="btn" type="submit" :disabled="submitting">
      {{ submitting ? "启动中…" : "生成人物·背景视频" }}
    </button>
    <div class="status">{{ status }}</div>
    <div class="vwarn">{{ voiceWarn }}</div>
    <div class="err">{{ error }}</div>
  </form>
</template>

<script setup lang="ts">
import { computed, inject, ref, watch } from "vue";
import { api, baseUrl, type MakeCharBgBody, type Provider, type ScriptDoc } from "../api";
import { useAppStore } from "../store";
import { uiAlert } from "../dialogs";
import EntityCard from "./EntityCard.vue";

// App 提供旁白声源卡 / BGM 卡的 ref 读取函数（提交时读取负载）
interface Cards {
  voice: () => { voicePayload(): { voice: string; local_audio: { _default: string } | null } } | null;
  bgm: () => { exportBgm(): { bgm: string; bgmVol: number; quality: string; subSize: string } } | null;
}
const cards = inject<Cards>("yuclCards", { voice: () => null, bgm: () => null });

export interface CbEntity {
  id: number; name: string; src: "prompt" | "upload";
  prompt: string; path: string; url: string; imgSrc: string; voice: string;
}
interface Shot {
  char: number; char2: number; bg: number; text: string; seconds: number; engine: string;
}

const store = useAppStore();
let uid = 0;
function makeEntity(kind: "chars" | "bgs"): CbEntity {
  const n = (kind === "chars" ? chars.value : bgs.value).length + 1;
  return { id: ++uid, name: (kind === "chars" ? "人物" : "背景") + n, src: "prompt", prompt: "", path: "", url: "", imgSrc: "", voice: "" };
}

const chars = ref<CbEntity[]>([makeEntity("chars"), makeEntity("chars")]);
const bgs = ref<CbEntity[]>([makeEntity("bgs")]);
const shots = ref<Shot[]>([{ char: chars.value[0].id, char2: chars.value[1].id, bg: bgs.value[0].id, text: "", seconds: 5, engine: "local" }]);

const orientation = ref("16:9");
const provId = ref("");
const videoModel = ref("");
const imgModel = ref("");
const charScale = ref(0.68);
const charPos = ref("bottom-center");
const subtitles = ref(true);
const narration = ref(true);
const submitting = ref(false);
const status = ref("");
const voiceWarn = ref("");
const error = ref("");

function provLabel(p: Provider) {
  let s = p.name || p.id;
  if (p.builtin) s += "（内置）";
  if (!p.has_key) s += "（未填 Key）";
  return s;
}
const activeProv = computed(() => store.providers.find(p => p.id === provId.value) || store.providers.find(p => p.id === store.activeProvider));
const videoModelList = computed(() => activeProv.value?.video_models || []);
const imgModelList = computed(() => activeProv.value?.image_models || []);

// 供应商/选项变化时同步默认模型
watch(() => [store.options, store.activeProvider, provId.value] as const, () => {
  const o = store.options;
  if (!o) return;
  if (!provId.value || !store.providers.some(p => p.id === provId.value)) provId.value = o.active_provider_id || "";
  const p = store.providers.find(x => x.id === provId.value);
  if (p?.default_video_model && !videoModelList.value.includes(videoModel.value)) videoModel.value = p.default_video_model;
  if (!videoModel.value && o.default_video_model) videoModel.value = o.default_video_model;
  if (p?.default_image_model && !imgModelList.value.includes(imgModel.value)) imgModel.value = p.default_image_model;
  if (!imgModel.value && o.default_image_model) imgModel.value = o.default_image_model;
}, { immediate: true });

function addEntity(kind: "chars" | "bgs") {
  const e = makeEntity(kind);
  (kind === "chars" ? chars : bgs).value.push(e);
}
function addShot() {
  shots.value.push({ char: 0, char2: 0, bg: bgs.value[0]?.id ?? 0, text: "", seconds: 5, engine: "local" });
}
function renderAll() {
  // 失效的镜头引用清理
  for (const sh of shots.value) {
    if (sh.char && !chars.value.some(c => c.id === sh.char)) sh.char = 0;
    if (sh.char2 && !chars.value.some(c => c.id === sh.char2)) sh.char2 = 0;
    if (sh.bg && !bgs.value.some(b => b.id === sh.bg)) sh.bg = 0;
  }
}

// 一键剧本 → 填入表单（供 ScriptPanel 调用）
function applyScript(s: ScriptDoc): string {
  chars.value = (s.characters || []).map(c => ({
    id: ++uid, name: c.name || "人物", src: "prompt", prompt: c.appearance || "",
    path: "", url: "", imgSrc: "", voice: c.voice || ""
  }));
  if (!chars.value.length) chars.value.push(makeEntity("chars"));

  bgs.value = [];
  const bgName = new Map<string, number>();
  for (const x of s.scenes || []) {
    const key = (x.background || "").trim();
    if (key && !bgName.has(key)) {
      const e = makeEntity("bgs");
      e.name = key.length > 12 ? key.slice(0, 12) + "…" : key;
      e.prompt = key + (x.shot ? "；画面：" + x.shot : "");
      bgName.set(key, e.id);
      bgs.value.push(e);
    }
  }
  if (!bgs.value.length) bgs.value.push(makeEntity("bgs"));

  const charId = new Map(chars.value.map(c => [c.name, c.id] as [string, number]));
  shots.value = (s.scenes || []).map(x => {
    const names = (x.character || "").split(/[,，、]/).map(s => s.trim()).filter(Boolean);
    const c1 = names[0] ? charId.get(names[0]) ?? 0 : 0;
    const c2 = names[1] ? charId.get(names[1]) ?? 0 : 0;
    return { char: c1, char2: c2, bg: 0, text: ((x.shot ? "[" + x.shot + "] " : "") + (x.dialogue || "").trim()), seconds: x.seconds || 5, engine: "local" };
  });
  (s.scenes || []).forEach((x, i) => {
    const key = (x.background || "").trim();
    shots.value[i].bg = key && bgName.has(key) ? bgName.get(key)! : (bgs.value[0]?.id ?? 0);
  });
  return "已填入 " + chars.value.length + " 人物 / " + bgs.value.length + " 背景 / " + shots.value.length +
    " 镜头（人物与背景图片还需「生成图片」或上传后再生成视频）";
}

async function submit() {
  status.value = "";
  error.value = "";
  voiceWarn.value = "";
  const charsP = chars.value.filter(c => c.path || c.url).map(c => ({
    name: c.name || "人物", image: c.path || "", url: c.url || "",
    source: c.src === "prompt" ? "prompt" : "upload"
  }));
  const bgsP = bgs.value.filter(b => b.path || b.url).map(b => ({
    name: b.name || "背景", image: b.path || "", url: b.url || "",
    source: b.src === "prompt" ? "prompt" : "upload"
  }));
  const byId = (arr: CbEntity[], id: number) => arr.find(x => x.id === +id) || ({} as CbEntity);
  const shotsP: MakeCharBgBody["shots"] = shots.value.map(sh => ({
    character: byId(chars.value, sh.char).name || "",
    character2: byId(chars.value, sh.char2).name || "",
    background: byId(bgs.value, sh.bg).name || "",
    text: sh.text || "", seconds: sh.seconds || 5, engine: sh.engine || "local"
  }));
  if (!charsP.length) { error.value = "✕ 请至少提供一张人物图片（上传或提示词生成）"; return; }
  if (!bgsP.length) { error.value = "✕ 请至少提供一张背景图片（上传或提示词生成）"; return; }
  if (!shotsP.length) { error.value = "✕ 请至少添加一个镜头"; return; }
  const anyAi = shots.value.some(sh => sh.engine === "ai");
  if (anyAi && !charsP.concat(bgsP).some(x => x.url)) {
    error.value = "✕ Agnes AI 引擎需要可联网参考图：请用「提示词」生成图片（得到公网 URL），或把该镜头改为「本地合成」";
    return;
  }
  const charVoices: Record<string, string> = {};
  for (const c of chars.value) if (c.voice) charVoices[c.name || "人物"] = c.voice;

  const vp = cards.voice()?.voicePayload() || { voice: "", local_audio: null };
  const bg = cards.bgm()?.exportBgm() || { bgm: "", bgmVol: 0.18, quality: "1080p", subSize: "medium" };
  const body: MakeCharBgBody = {
    characters: charsP, backgrounds: bgsP, shots: shotsP,
    provider_id: provId.value,
    orientation: orientation.value, model: videoModel.value,
    char_scale: +charScale.value || 0.68, char_pos: charPos.value,
    subtitles: subtitles.value, narration: narration.value,
    default_voice: vp.voice, local_audio: vp.local_audio,
    char_voices: charVoices,
    bgm: bg.bgm, bgm_vol: bg.bgmVol,
    quality: bg.quality, sub_size: bg.subSize
  };

  submitting.value = true;
  status.value = anyAi ? "启动任务（Agnes 逐镜生成 + 合成，免费档可能排队 1–3 分钟/镜）…" : "启动任务（本地合成：Ken-Burns + 人物叠加 + 配音）…";
  try {
    const j = await api.makeCharBg(body);
    if (j.ok && j.job_id) {
      status.value = "✅ 任务已开始（编号 " + j.job_id.slice(-8) + "）· 进度 / 取消 / 重试见下方「近期作品」";
      store.loadJobs();
    } else {
      error.value = "✕ " + (j.error || j.detail || "启动失败");
      status.value = "";
    }
  } catch (e: any) {
    error.value = "✕ " + (e?.message || e);
    status.value = "";
  }
  submitting.value = false;
}

defineExpose({ applyScript });
</script>
