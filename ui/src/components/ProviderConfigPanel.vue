<template>
  <div class="pane" style="margin-bottom:14px">
    <details open>
      <summary style="cursor:pointer;list-style:none">
        <span class="cb-sec-h">模型配置 <span class="hint" style="font-weight:400">各家模型供应商，可自主切换</span></span>
      </summary>
      <div style="margin-top:8px">
        <div v-for="(p, i) in provs" :key="p.id" class="prov-card" :data-i="i">
          <div class="srow">
            <label style="display:flex;gap:6px;align-items:center;font-size:12px;color:var(--fg2);flex:none;width:auto">
              <input type="radio" :name="'prov-active-' + i" :value="p.id" :checked="String(active) === String(p.id)"
                     @change="active = String(p.id)">使用此供应商
            </label>
            <div style="flex:1;display:flex;gap:6px;min-width:0">
              <input v-model="p.name" placeholder="供应商名称">
              <span v-if="p.builtin" class="tag" style="flex:none">内置</span>
              <button v-else type="button" data-act="delprov" title="删除此供应商" @click="delProv(i)">×</button>
            </div>
          </div>
          <div class="srow">
            <input v-model="p.base_url" placeholder="Base URL 如 https://api.openai.com/v1">
            <input v-model="p.api_key" type="password" placeholder="API Key">
          </div>
          <div class="srow">
            <input v-model="p.imageModels" placeholder="生图模型（逗号分隔）">
            <input v-model="p.default_image_model" placeholder="默认生图模型">
          </div>
          <div class="srow">
            <input v-model="p.videoModels" placeholder="视频模型（逗号分隔）">
            <input v-model="p.default_video_model" placeholder="默认视频模型">
          </div>
          <div class="srow">
            <input v-model="p.textModels" placeholder="文本模型（写剧本，逗号分隔）">
            <input v-model="p.default_text_model" placeholder="默认文本模型">
          </div>
          <div class="srow">
            <button type="button" @click="test(i)">测试连接</button>
            <span class="hint" style="font-size:11px;white-space:pre-wrap">{{ tests[i] || "" }}</span>
          </div>
        </div>
        <div style="display:flex;gap:8px;margin-top:4px">
          <button type="button" class="cb-addbtn" style="width:auto;padding:8px 14px" @click="addProv">＋ 添加供应商</button>
          <button type="button" class="btn" @click="save">保存模型配置</button>
        </div>
        <div class="status">{{ status }}</div>
        <div style="font-size:11px;color:var(--dim2);margin-top:8px;line-height:1.6">
          生图模型通用 OpenAI 兼容服务（/images/generations）；视频模型仅 Agnes 协议网关可用，其他公司请用「本地合成」引擎。<br>
          API Key 只保存在本机文件（YUCL 数据目录下的 provider-config.json），不会上传任何地方。
        </div>
      </div>
    </details>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { api, type Provider } from "../api";
import { useAppStore } from "../store";

const store = useAppStore();
// 可编辑副本：数组字段（image_models 等）映射为逗号分隔字符串
interface ProvEdit extends Provider {
  imageModels: string;
  videoModels: string;
  textModels: string;
}
const provs = reactive<ProvEdit[]>([]);
const active = ref("");
const tests = reactive<Record<number, string>>({});
const status = ref("");

function load() {
  provs.splice(0, provs.length,
    ...store.providers.map(p => ({
      ...p,
      imageModels: listStr(p.image_models),
      videoModels: listStr(p.video_models),
      textModels: listStr(p.text_models)
    })));
  if (String(active.value) === "undefined" || !store.providers.some(p => p.id === active.value)) active.value = store.activeProvider;
}
// 首次 mount 时 store.providers 可能还没回来（App 并行加载），跟随其变化重建
watch(() => store.providers, () => {
  if (!provs.length || provs.length !== store.providers.length) load();
});
function listStr(a?: string[]) { return Array.isArray(a) ? a.join(", ") : String(a || ""); }
function parseList(s?: string) { return String(s || "").split(/[,，]/).map(x => x.trim()).filter(Boolean); }

function read(i: number): Provider {
  const p = provs[i];
  const out: Provider = { ...p };
  delete (out as any).imageModels;
  delete (out as any).videoModels;
  delete (out as any).textModels;
  out.image_models = parseList(p.imageModels);
  out.video_models = parseList(p.videoModels);
  out.text_models = parseList(p.textModels);
  return out;
}
function addProv() {
  provs.push({
    id: "p" + Date.now(), name: "新供应商", base_url: "", api_key: "",
    image_models: [], default_image_model: "", video_models: [], default_video_model: "",
    text_models: [], default_text_model: "",
    imageModels: "", videoModels: "", textModels: ""
  });
}
function delProv(i: number) {
  const id = String(provs[i].id);
  provs.splice(i, 1);
  if (active.value === id && provs.length) active.value = String(provs[0].id);
}
async function test(i: number) {
  tests[i] = "测试中…";
  let j: Awaited<ReturnType<typeof api.testProvider>>;
  try {
    j = await api.testProvider(read(i));
  } catch (e: any) {
    tests[i] = "✕ " + (e?.message || e);
    return;
  }
  if (j.ok) {
    let t = "✓ " + (j.hint || "连接正常");
    if (Array.isArray(j.models) && j.models.length) t += "\n" + j.models.slice(0, 12).join(" · ") + (j.models.length > 12 ? " …" : "");
    tests[i] = t;
  } else tests[i] = "✕ " + (j.error || j.detail || "未知错误");
}
async function save() {
  status.value = "保存中…";
  const body = {
    active: active.value,
    providers: provs.map((_, i) => read(i))
  };
  let j: Awaited<ReturnType<typeof api.saveProviderConfig>>;
  try {
    j = await api.saveProviderConfig(body);
  } catch (e: any) {
    status.value = "✕ " + (e?.message || e);
    return;
  }
  if (j.ok) {
    status.value = "已保存 · 共 " + j.count + " 家供应商，当前使用：" + j.active;
    await store.loadOptions();
    load();
  } else status.value = "✕ " + (j.error || j.detail || "保存失败");
}

load();
defineExpose({ reload: load });
</script>
