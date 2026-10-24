<template>
  <div class="pane" style="margin-bottom:14px">
    <details open>
      <summary style="cursor:pointer;list-style:none">
        <span class="cb-sec-h">一键剧本 <span class="hint" style="font-weight:400">LLM 写故事+台词，一键填好下方表单</span></span>
      </summary>
      <div style="margin-top:8px">
        <div class="lbl" style="margin-bottom:5px">故事主题 / 想法</div>
        <textarea v-model="topic" rows="3" placeholder="例：60 秒校园能量饮料宣传片，面向大学生，风格热血有趣"
          style="width:100%;box-sizing:border-box;font-size:13.5px;padding:8px;background:var(--panel);border:1px solid var(--border);border-radius:7px;color:var(--fg);resize:vertical"></textarea>
        <div class="srow" style="margin-top:6px">
          <select v-model="style">
            <option>产品推介</option><option>故事短剧</option><option>知识科普</option><option>生活 Vlog</option><option>其他</option>
          </select>
          <select v-model="shots">
            <option :value="4">4 镜</option><option :value="5">5 镜</option><option :value="6">6 镜</option>
            <option :value="8">8 镜</option><option :value="10">10 镜</option><option :value="12">12 镜</option>
          </select>
        </div>
        <div class="srow" style="margin-top:6px">
          <ProviderModelPicker :providers="store.providers" :active-provider="store.activeProvider"
            :configured="!!store.options?.providers_configured"
            v-model:provider-id="prov" v-model:model="model" kind="text"
            prov-id="sc-prov" model-id="sc-model" :placeholder="'（该供应商未配置文本模型，请填模型配置）'" />
        </div>
        <div style="display:flex;gap:8px;margin-top:8px;flex-wrap:wrap">
          <button type="button" class="btn" style="width:auto;padding:10px 18px" :disabled="busy" @click="gen">
            {{ busy ? "生成中…" : "生成剧本" }}
          </button>
          <button v-if="script" type="button" class="cb-addbtn" style="width:auto;padding:8px 14px" @click="apply">填入下方表单</button>
          <button v-if="script || raw" type="button" class="cb-addbtn" style="width:auto;padding:8px 14px" @click="copy">复制剧本</button>
        </div>
        <div class="status">{{ status }}</div>
        <div v-if="script" style="margin-top:8px;font-size:13.5px;line-height:1.7">
          <b style="font-size:15px">{{ script.title || "未命名" }}</b>
          <div v-if="script.logline" class="hint" style="margin-top:2px">{{ script.logline }}</div>
          <div style="margin-top:8px;font-weight:700">人物</div>
          <div v-for="c in script.characters" :key="c.name">
            · <b>{{ c.name }}</b> — {{ c.appearance || "" }}{{ c.voice ? `（音色：${c.voice}）` : "" }}
          </div>
          <div v-if="!script.characters.length" class="hint">（无）</div>
          <div style="margin-top:8px;font-weight:700">镜头</div>
          <div v-for="(x, i) in script.scenes" :key="i" style="margin-top:4px">
            {{ i + 1 }}. {{ x.background ? `[背景 ${x.background}] ` : "" }}{{ x.shot ? `[画面 ${x.shot}] ` : "" }}
            <b>{{ x.dialogue || "" }}</b>
            {{ x.seconds ? ` · ${x.seconds}s` : "" }}{{ x.character ? ` · <span class="tag">${x.character}</span>` : "" }}
          </div>
        </div>
        <pre v-else-if="raw" style="white-space:pre-wrap;font-size:13px;color:var(--fg2);margin:8px 0 0">{{ raw }}</pre>
      </div>
    </details>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { api, type ScriptDoc } from "../api";
import { useAppStore } from "../store";
import { uiPrompt } from "../dialogs";
import ProviderModelPicker from "./ProviderModelPicker.vue";

const props = defineProps<{ scriptToForm?: (s: ScriptDoc) => string }>();
const emit = defineEmits<{ (e: "applied", s: ScriptDoc, msg: string): void }>();

const store = useAppStore();
const topic = ref("");
const style = ref("产品推介");
const shots = ref(6);
const prov = ref("");
const model = ref("");
const busy = ref(false);
const status = ref("");
const script = ref<ScriptDoc | null>(null);
const raw = ref("");

function scriptToText(s: ScriptDoc) {
  const L = [s.title || "未命名", s.logline || ""].filter(Boolean) as string[];
  L.push("", "人物：");
  (s.characters || []).forEach(c => L.push("· " + (c.name || "") + " — " + (c.appearance || "") + (c.voice ? "（音色：" + c.voice + "）" : "")));
  L.push("", "镜头：");
  (s.scenes || []).forEach((x, i) => L.push(
    (i + 1) + ". [背景 " + (x.background || "") + "] " + (x.shot ? "[画面 " + x.shot + "] " : "") +
    (x.dialogue || "") + "（约 " + (x.seconds || 5) + " 秒）" + (x.character ? " · 人物：" + x.character : "")));
  return L.join("\n");
}

async function gen() {
  if (!topic.value.trim()) { status.value = "✕ 先输入故事主题"; return; }
  busy.value = true;
  script.value = null;
  raw.value = "";
  status.value = "LLM 写剧本中（约 10–60 秒）…" + (model.value ? " · " + model.value : "");
  try {
    const j = await api.script({ topic: topic.value.trim(), style: style.value, shots: shots.value || 6, extra: "", model: model.value, provider_id: prov.value });
    if (j.ok && j.script) {
      script.value = j.script;
      status.value = "已生成 · " + (j.provider || "") + " · " + j.script.scenes.length + " 镜" + (j.model ? " · " + j.model : "");
    } else if (j.ok && j.raw) {
      raw.value = j.raw;
      status.value = "模型没给出结构化 JSON，已展示原文，可手动对照填入";
    } else {
      status.value = "✕ " + (j.error || j.detail || "生成失败");
    }
  } catch (e: any) {
    status.value = "✕ " + (e?.message || e);
  }
  busy.value = false;
}
async function copy() {
  const s = script.value;
  const txt = s ? scriptToText(s) : raw.value;
  if (!txt) return;
  try {
    await navigator.clipboard.writeText(txt);
    status.value = "已复制到剪贴板";
  } catch {
    uiPrompt("复制失败，请手动全选复制：", txt);
  }
}
function apply() {
  const s = script.value;
  if (!s) return; // 只有原始输出（解析失败）时不可填入表单
  if (props.scriptToForm) {
    status.value = props.scriptToForm(s);
    emit("applied", s, status.value);
  }
}
</script>
