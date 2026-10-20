<template>
  <select :id="id" :value="modelValue" class="prov-select" :title="title || '选择模型供应商'" @change="onChange">
    <option v-for="p in providers" :key="p.id" :value="p.id">{{ label(p) }}</option>
  </select>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { Provider } from "../api";

const props = defineProps<{
  id?: string;
  title?: string;
  providers: Provider[];
  modelValue: string;
}>();
const emit = defineEmits<{ (e: "update:modelValue", v: string): void }>();

function label(p: Provider) {
  let s = p.name || p.id;
  if (p.builtin) s += "（内置）";
  if (!p.has_key) s += "（未填 Key）";
  return s;
}
function onChange(ev: Event) {
  emit("update:modelValue", (ev.target as HTMLSelectElement).value);
}
</script>
