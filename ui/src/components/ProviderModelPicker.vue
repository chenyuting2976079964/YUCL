<template>
  <div class="provider-model-pair">
    <div class="field">
      <label class="lbl" :title="provTitle">模型供应商</label>
      <select :id="provId" :value="providerId" @change="onProvChange">
        <option v-for="p in providers" :key="p.id" :value="p.id">{{ provLabel(p) }}</option>
      </select>
    </div>
    <div class="field">
      <label class="lbl"><slot name="model-label">模型</slot></label>
      <select :id="modelId" :value="model" @change="onModelChange">
        <option v-for="m in modelList" :key="m" :value="m">{{ m }}</option>
        <option v-if="!modelList.length" value="">{{ placeholder }}</option>
      </select>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { Provider } from "../api";

const props = defineProps<{
  provId?: string;
  modelId?: string;
  provTitle?: string;
  placeholder?: string;
  providers: Provider[];
  activeProvider: string;
  providerId: string;
  model: string;
  kind: "video" | "image" | "text";
}>();
const emit = defineEmits<{
  (e: "update:providerId", v: string): void;
  (e: "update:model", v: string): void;
}>();

function provLabel(p: Provider) {
  let s = p.name || p.id;
  if (p.builtin) s += "（内置）";
  if (!p.has_key) s += "（未填 Key）";
  return s;
}
function providerOf(id: string) {
  return props.providers.find(p => p.id === id) || undefined;
}
const modelList = computed(() => {
  const p = providerOf(props.providerId);
  if (!p) return [];
  const pAny = p as unknown as Record<string, string[] | undefined>;
  return pAny[props.kind + "_models"] || [];
});
function onProvChange(e: Event) {
  const pid = (e.target as HTMLSelectElement).value;
  emit("update:providerId", pid);
  const p = providerOf(pid);
  const dv = p ? ((p as unknown as Record<string, string | undefined>)["default_" + props.kind + "_model"]) || "" : "";
  // 无条件同步：切到没有默认模型的供应商时清空旧值，避免拿旧模型去调新供应商
  emit("update:model", dv);
}
function onModelChange(e: Event) {
  emit("update:model", (e.target as HTMLSelectElement).value);
}
</script>
