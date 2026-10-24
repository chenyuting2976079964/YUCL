<template>
  <form id="f-charbg" class="pane flow" @submit.prevent="submit">
    <!-- 步骤指示器 -->
    <div class="flow-steps">
      <div v-for="(st, i) in steps" :key="st.k" class="fstep" :class="{ done: st.done, cur: i === curIdx }">
        <div class="fnum">{{ st.done ? "✓" : st.n }}</div>
        <div class="flbl">{{ st.name }}</div>
      </div>
    </div>

    <div class="flow-sec" id="step1">
      <div class="cb-sec-h"><span class="stepbadge">①</span> {{ t("step1") }} <span class="hint" style="font-weight:400">{{ t("step1_hint") }}</span></div>
      <EntityCard v-for="e in chars" :key="e.id" :entity="e" kind="chars" :options="store.options"
        :voices="store.voices" :prov-id="provId" :img-model="imgModel" @removed="renderAll" />
      <button type="button" class="cb-addbtn" @click="addEntity('chars')">{{ t("add_char") }}</button>
    </div>

    <div class="flow-sec" id="step2">
      <div class="cb-sec-h"><span class="stepbadge">②</span> {{ t("step2") }} <span class="hint" style="font-weight:400">{{ t("step2_hint") }}</span></div>
      <EntityCard v-for="e in bgs" :key="e.id" :entity="e" kind="bgs" :options="store.options"
        :voices="store.voices" :prov-id="provId" :img-model="imgModel" @removed="renderAll" />
      <button type="button" class="cb-addbtn" @click="addEntity('bgs')">{{ t("add_bg") }}</button>
    </div>

    <div class="flow-sec" id="step3">
      <div class="cb-sec-h"><span class="stepbadge">③</span> {{ t("step3") }} <span class="hint" style="font-weight:400">{{ t("step3_hint") }}</span></div>
      <div v-for="(sh, i) in shots" :key="i" class="cb-shot" :data-i="i">
        <div class="srow">
          <select v-model="sh.char" :title="t('char1')">
            <option :value="0">{{ t("none") }}</option>
            <option v-for="c in chars" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <select v-model="sh.char2" :title="t('char2')">
            <option :value="0">{{ t("none") }}</option>
            <option v-for="c in chars" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </div>
        <div class="srow">
          <select v-model="sh.bg" :title="t('bg_label')">
            <option :value="0">{{ t("none") }}</option>
            <option v-for="b in bgs" :key="b.id" :value="b.id">{{ b.name }}</option>
          </select>
          <input v-model.number="sh.seconds" type="number" min="4" max="12" :title="t('duration')">
        </div>
        <div class="srow"><input class="full" v-model="sh.text" :placeholder="t('action_ph')"></div>
        <div class="srow">
          <select v-model="sh.engine">
            <option value="local">{{ t("engine_local") }}</option>
            <option value="ai">{{ t("engine_ai") }}</option>
          </select>
          <button type="button" class="cb-del" @click="shots.splice(i, 1)">{{ t("del_shot") }}</button>
        </div>
      </div>
      <button type="button" class="cb-addbtn" @click="addShot">{{ t("add_shot") }}</button>
    </div>

    <div class="flow-sec" id="step4">
      <div class="cb-sec-h"><span class="stepbadge">④</span> {{ t("step4") }} <span class="hint" style="font-weight:400">{{ t("step4_hint") }}</span></div>
      <div class="row">
        <div class="field">
          <label class="lbl">{{ t("orientation") }}</label>
          <select v-model="orientation">
            <option v-for="o in store.options?.orientations || []" :key="o.key" :value="o.key">{{ o.title || o.label || o.key }}</option>
          </select>
        </div>
        <div class="field">
          <label class="lbl" title="开放 harness：生成走哪家模型网关（设置→模型配置 可增删）">{{ t("provider") }}</label>
          <select v-model="provId">
            <option v-for="p in store.providers" :key="p.id" :value="p.id">{{ provLabel(p) }}</option>
          </select>
        </div>
        <div class="field">
          <label class="lbl">{{ t("video_model") }} <span class="hint">ai 引擎</span></label>
          <select v-model="videoModel">
            <option v-for="m in videoModelList" :key="m" :value="m">{{ m || t("default_model") }}</option>
          </select>
        </div>
        <div class="field">
          <label class="lbl">{{ t("img_model") }}</label>
          <select v-model="imgModel">
            <option v-for="m in imgModelList" :key="m" :value="m">{{ m || t("default_model") }}</option>
          </select>
        </div>
      </div>
      <div class="row">
        <div class="field">
          <label class="lbl">{{ t("char_scale") }}</label>
          <input type="range" v-model.number="charScale" min="0.4" max="0.9" step="0.05">
          <div style="font-size:12px;color:var(--dim2);margin-top:4px">占画面高度比例 · 越大越近 <b style="color:var(--fg)">{{ charScale.toFixed(2) }}</b></div>
        </div>
        <div class="field">
          <label class="lbl">{{ t("char_pos") }}</label>
          <select v-model="charPos">
            <option value="bottom-left">{{ t("pos_bl") }}</option>
            <option value="bottom-center">{{ t("pos_bc") }}</option>
            <option value="bottom-right">{{ t("pos_br") }}</option>
            <option value="center">{{ t("pos_c") }}</option>
          </select>
          <div style="font-size:12px;color:var(--dim2);margin-top:4px">两人同框时自动左/右分置</div>
        </div>
      </div>
      <label class="check"><input type="checkbox" v-model="subtitles"> {{ t("subtitles") }}</label>
      <label class="check"><input type="checkbox" v-model="narration"> {{ t("narration") }}</label>
      <button class="btn big" type="submit" :disabled="submitting">
        🎬 {{ submitting ? t("starting") : t("gen_video") }}
      </button>
      <div class="status">{{ status }}</div>
      <div class="vwarn">{{ voiceWarn }}</div>
      <div class="err">{{ error }}</div>
    </div>
  </form>
</template>

<script setup lang="ts">
import { computed, inject, ref, watch } from "vue";
import { api, baseUrl, type MakeCharBgBody, type Provider, type ScriptDoc } from "../api";
import { useAppStore } from "../store";
import { uiAlert } from "../dialogs";
import { t } from "../i18n";
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

const chars = ref<CbEntity[]>([]);
const bgs = ref<CbEntity[]>([]);
// 先建空数组再填充：makeEntity 引用 chars/bgs.value，必须在二者初始化之后调用（TDZ）
chars.value = [makeEntity("chars"), makeEntity("chars")];
bgs.value = [makeEntity("bgs")];
const shots = ref<Shot[]>([{ char: chars.value[0].id, char2: chars.value[1].id, bg: bgs.value[0].id, text: "", seconds: 5, engine: "local" }]);

const orientation = ref("9:16"); // 短视频以竖屏为主 → 默认竖屏（用户仍可切换）
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
  if (!p.has_key) s += "（未填 Key）";
  return s;
}
const activeProv = computed(() => store.providers.find(p => p.id === provId.value) || store.providers.find(p => p.id === store.activeProvider));
// 模型信息显示策略：用户配置过供应商才显示具体模型名；未配置时只留"系统默认模型"通用项（后端自行取默认模型）
const configured = computed(() => !!store.options?.providers_configured);
const videoModelList = computed(() => configured.value ? (activeProv.value?.video_models || []) : [""]);
const imgModelList = computed(() => configured.value ? (activeProv.value?.image_models || []) : [""]);

// 步骤指示器：①人物图 ②场景图 ③内容描述 ④生成视频
const steps = computed(() => [
  { k: "c", n: 1, name: t("step1"), done: chars.value.some(c => c.path || c.url) },
  { k: "b", n: 2, name: t("step2"), done: bgs.value.some(b => b.path || b.url) },
  { k: "s", n: 3, name: t("step3"), done: shots.value.some(s => s.text.trim() !== "") },
  { k: "g", n: 4, name: t("step4"), done: false }
]);
// 当前待办步骤（第一个未完成项）→ UI 高亮"现在该做哪一步"
const curIdx = computed(() => steps.value.findIndex(s => !s.done));

// 供应商/选项变化时同步默认模型
watch(() => [store.options, store.activeProvider, provId.value] as const, () => {
  const o = store.options;
  if (!o) return;
  if (!provId.value || !store.providers.some(p => p.id === provId.value)) provId.value = o.active_provider_id || "";
  if (!o.providers_configured) { videoModel.value = ""; imgModel.value = ""; return; } // 未配置：不显示模型信息，后端取系统默认
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
  // 去重/补名：重名会让后端 entitiesMap 与镜头按名查找时后者覆盖前者
  const used = new Set<string>();
  chars.value = (s.characters || []).map((c, i) => {
    let nm = ((c.name || "").trim() || ("人物" + (i + 1)));
    if (used.has(nm)) nm = nm + " " + (i + 1);
    used.add(nm);
    return {
      id: ++uid, name: nm, src: "prompt" as const, prompt: c.appearance || "",
      path: "", url: "", imgSrc: "",
      // LLM 给的 voice 多是描述词（「成熟女声」），不是可选的 tts:/file: 值 → 只保留合法前缀
      voice: /^(tts:|file:)/.test(c.voice || "") ? (c.voice as string) : ""
    };
  });
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
    error.value = "✕ AI 引擎需要可联网参考图：请用「提示词」生成图片（得到公网 URL），或把该镜头改为「本地合成」";
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
  status.value = anyAi ? "启动任务（AI 模型逐镜生成 + 合成，高峰期可能排队 1–3 分钟/镜）…" : "启动任务（本地合成：Ken-Burns + 人物叠加 + 配音）…";
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

defineExpose({
  applyScript,
  // 项目制：导出表单完整状态（人物/背景/镜头/画面设置），供 App 组装项目文件
  getProjectState() {
    const entOut = (e: CbEntity) => ({
      name: e.name, src: e.src, prompt: e.prompt,
      image: e.path ? e.path.split(/[\\/]/).pop() || "" : "",
      url: e.url || "",
      path: e.path || "",
      voice: e.voice || ""
    });
    return {
      characters: chars.value.map(entOut),
      backgrounds: bgs.value.map(entOut),
      shots: shots.value.map(s => ({ ...s })),
      orientation: orientation.value,
      providerId: provId.value,
      videoModel: videoModel.value,
      imgModel: imgModel.value,
      charScale: charScale.value,
      charPos: charPos.value,
      subtitles: subtitles.value,
      narration: narration.value
    };
  },
  // 项目制：回填表单（"image" 为 media 库裸文件名，后端打开项目时已物化回媒体库）
  applyProjectState(form: Record<string, any>) {
    const f = form || {};
    const mk = (list: any[], kind: "chars" | "bgs") =>
      (Array.isArray(list) ? list : []).map((e, i) => {
        const image: string = e?.image || "";
        const assetUrl: string = e?.asset_url || "";
        let imgSrc = "";
        if (assetUrl) imgSrc = baseUrl + assetUrl;
        else if (image) imgSrc = baseUrl + "/images/preview?name=" + encodeURIComponent(image);
        else imgSrc = e?.url || "";
        return {
          id: e?.id || ++uid,
          name: e?.name || ((kind === "chars" ? "人物" : "背景") + (i + 1)),
          src: (e?.src === "upload" ? "upload" : "prompt") as "prompt" | "upload",
          prompt: e?.prompt || "",
          path: image || "",          // 裸文件名 → /make-char-bg 按 media 库解析；空则靠 url
          url: e?.url || "",
          imgSrc,
          voice: e?.voice || ""
        };
      });
    chars.value = mk(f.characters, "chars");
    bgs.value = mk(f.backgrounds, "bgs");
    if (!chars.value.length) chars.value.push(makeEntity("chars"));
    if (!bgs.value.length) bgs.value.push(makeEntity("bgs"));
    uid = Math.max(uid, ...chars.value.map(c => c.id), ...bgs.value.map(b => b.id));
    shots.value = (Array.isArray(f.shots) && f.shots.length ? f.shots : [])
      .map((s: any) => ({
        char: +s?.char || 0, char2: +s?.char2 || 0, bg: +s?.bg || 0,
        text: s?.text || "", seconds: +s?.seconds || 5, engine: s?.engine || "local"
      }));
    if (!shots.value.length) shots.value.push({ char: chars.value[0].id, char2: chars.value[1]?.id || 0, bg: bgs.value[0].id, text: "", seconds: 5, engine: "local" });
    if (f.orientation) orientation.value = f.orientation;
    if (typeof f.providerId === "string") provId.value = f.providerId;
    if (typeof f.videoModel === "string") videoModel.value = f.videoModel;
    if (typeof f.imgModel === "string") imgModel.value = f.imgModel;
    if (typeof f.charScale === "number") charScale.value = f.charScale;
    if (typeof f.charPos === "string") charPos.value = f.charPos;
    if (typeof f.subtitles === "boolean") subtitles.value = f.subtitles;
    if (typeof f.narration === "boolean") narration.value = f.narration;
    renderAll();
  },
  // 新建项目：恢复默认空模板（与首次启动一致）
  resetToTemplate() {
    uid = 0;
    chars.value = []; bgs.value = [];
    chars.value = [makeEntity("chars"), makeEntity("chars")];
    bgs.value = [makeEntity("bgs")];
    shots.value = [{ char: chars.value[0].id, char2: chars.value[1].id, bg: bgs.value[0].id, text: "", seconds: 5, engine: "local" }];
    orientation.value = "9:16"; provId.value = ""; videoModel.value = ""; imgModel.value = "";
    charScale.value = 0.68; charPos.value = "bottom-center"; subtitles.value = true; narration.value = true;
  }
});
</script>
