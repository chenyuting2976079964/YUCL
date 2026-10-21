<template>
  <div class="sm-overlay" @click.self="emit('close')">
    <div class="sm-box">
      <div class="sm-head">
        <div class="sm-title">⚙ 设置</div>
        <button class="sm-x" type="button" @click="emit('close')">✕</button>
      </div>
      <div class="sm-tabs">
        <button v-for="tab in tabs" :key="tab.k" type="button" :class="{ on: tab.k === cur }" @click="cur = tab.k">{{ tab.n }}</button>
      </div>
      <div class="sm-body">
        <!-- ── 语言 ── -->
        <div v-if="cur === 'lang'" class="sm-sec">
          <div class="sm-lbl">界面语言 <span class="sm-hint">即时生效</span></div>
          <div class="sm-rows">
            <label class="sm-row"><input type="radio" :checked="i18n.lang === 'zh'" @change="setLang('zh')"> 简体中文</label>
            <label class="sm-row"><input type="radio" :checked="i18n.lang === 'en'" @change="setLang('en')"> English</label>
          </div>
        </div>

        <!-- ── 外观 ── -->
        <div v-if="cur === 'app'" class="sm-sec">
          <div class="sm-lbl">主题色</div>
          <div class="sw-row">
            <button v-for="a in ACCENTS" :key="a.c" type="button" class="sw" :class="{ on: appearance.accent === a.c }"
                    :style="{ background: a.c }" :title="a.n" @click="setAccent(a.c)"></button>
          </div>
          <div class="sm-lbl">字号</div>
          <div class="seg">
            <button type="button" :class="{ on: appearance.font === 'std' }" @click="setFont('std')">标准</button>
            <button type="button" :class="{ on: appearance.font === 'large' }" @click="setFont('large')">大字号</button>
          </div>
          <div class="sm-lbl">窗口密度</div>
          <div class="seg">
            <button type="button" :class="{ on: appearance.density === 'comfort' }" @click="setDensity('comfort')">舒适（默认）</button>
            <button type="button" :class="{ on: appearance.density === 'compact' }" @click="setDensity('compact')">紧凑</button>
          </div>
          <div class="sm-lbl">窗口</div>
          <button type="button" class="btn" style="width:100%;justify-content:center" @click="resetWindowSize">恢复默认窗口大小（窗口已可拖拽边缘缩放）</button>
        </div>

        <!-- ── 模型配置 ── -->
        <div v-if="cur === 'model'" class="sm-sec">
          <div class="sm-hint" style="margin-bottom:10px">各家模型供应商，可自主切换；Key 仅保存在本地</div>
          <ProviderConfigPanel ref="provPanel" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import ProviderConfigPanel from "./ProviderConfigPanel.vue";
import { ACCENTS, appearance, applyAppearance, i18n, resetWindowSize, setLang } from "../i18n";

const emit = defineEmits<{ (e: "close"): void }>();
const tabs = [
  { k: "lang", n: "语言" },
  { k: "app", n: "外观" },
  { k: "model", n: "模型配置" }
] as Array<{ k: "lang" | "app" | "model"; n: string }>;
const cur = ref<"lang" | "app" | "model">("app");
const provPanel = ref<InstanceType<typeof ProviderConfigPanel> | null>(null);

function persist() { applyAppearance({ ...appearance }); }
function setAccent(c: string) { appearance.accent = c; persist(); }
function setFont(f: "std" | "large") { appearance.font = f; persist(); }
function setDensity(d: "comfort" | "compact") { appearance.density = d; persist(); }

// 打开模型配置页时刷新供应商列表（ProviderConfigPanel 暴露 reload）
watch(cur, v => {
  if (v === "model" && provPanel.value && (provPanel.value as any).reload) (provPanel.value as any).reload();
});
</script>

<style scoped>
.sm-overlay{position:fixed;inset:0;background:rgba(4,6,12,.62);backdrop-filter:blur(3px);z-index:90;display:flex;align-items:flex-start;justify-content:center;padding:48px 16px}
.sm-box{width:560px;max-width:94vw;max-height:82vh;display:flex;flex-direction:column;background:var(--bg);border:1px solid var(--border);
  border-radius:14px;box-shadow:0 24px 60px rgba(0,0,0,.55);overflow:hidden}
.sm-head{display:flex;align-items:center;justify-content:space-between;padding:14px 18px;border-bottom:1px solid var(--border)}
.sm-title{font-size:14px;font-weight:700;color:var(--fg)}
.sm-x{width:28px;height:28px;border-radius:8px;border:1px solid var(--border);background:transparent;color:var(--dim);cursor:pointer;font-size:13px}
.sm-x:hover{background:#1a2138;color:var(--fg)}
.sm-tabs{display:flex;gap:4px;padding:10px 14px 0}
.sm-tabs button{padding:7px 14px;border-radius:999px;border:1px solid transparent;background:transparent;color:var(--dim);font-size:12.5px;font-weight:600;cursor:pointer}
.sm-tabs button.on{color:var(--accent);border-color:color-mix(in srgb, var(--accent) 45%, transparent);background:color-mix(in srgb, var(--accent) 12%, transparent)}
.sm-body{padding:14px 18px 20px;overflow:auto;flex:1}
.sm-sec{animation:fade .18s ease}
@keyframes fade{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:none}}
.sm-lbl{font-size:12px;font-weight:700;color:var(--fg2);margin:14px 0 8px;letter-spacing:.02em}
.sm-lbl:first-child{margin-top:2px}
.sm-hint{font-size:11px;color:var(--dim2)}
.sm-rows{display:flex;gap:16px}
.sm-row{display:flex;align-items:center;gap:7px;font-size:12.5px;color:var(--fg2);cursor:pointer}
.sw-row{display:flex;gap:10px}
.sw{width:30px;height:30px;border-radius:9px;border:2px solid transparent;cursor:pointer}
.sw.on{border-color:var(--fg);box-shadow:0 0 0 3px color-mix(in srgb, var(--accent) 30%, transparent)}
.seg{display:flex;gap:8px}
.seg button{padding:7px 14px;border-radius:8px;border:1px solid var(--border);background:transparent;color:var(--dim);font-size:12px;cursor:pointer}
.seg button.on{color:var(--fg);border-color:var(--accent);background:color-mix(in srgb, var(--accent) 14%, transparent)}
[data-density="compact"] .sm-box{width:480px}
</style>
