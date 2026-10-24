<template>
  <div class="proj-home">
    <div class="ph-hero">
      <div class="ph-brand">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="8.5" cy="8" r="3"/><path d="M3 20c0-3.3 2.7-6 5.5-6S14 16.7 14 20"/><path d="M15 5.5A3.5 3.5 0 0 1 15 12M18 20c0-2.4 1-4.5 3-5.5"/></svg>
        <div>
          <div class="ph-title">YUCL</div>
          <div class="ph-sub">{{ t("app_sub") }}</div>
        </div>
      </div>
      <button class="ph-new" @click="onNew">
        <span class="ph-new-plus">＋</span>
        <span>{{ t("proj_new") }}</span>
      </button>
    </div>

    <div class="ph-recent">
      <div class="ph-recent-title">{{ t("proj_recent") }}</div>
      <div v-if="!items.length" class="ph-empty">{{ t("proj_empty") }}</div>
      <div v-for="p in items" :key="String(p.id)" class="ph-card">
        <div class="ph-card-body" @click="onOpen(p)">
          <div class="ph-card-name">{{ p.name }}</div>
          <div class="ph-card-meta">{{ metaOf(p) }}</div>
        </div>
        <div class="ph-card-actions">
          <button class="btn" @click="onOpen(p)">{{ t("proj_open") }}</button>
          <button class="btn danger" @click="onDelete(p)">{{ t("proj_del") }}</button>
        </div>
      </div>
    </div>
    <div class="ph-err">{{ err }}</div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../api";
import { t } from "../i18n";
import { uiAlert, uiConfirm, uiPrompt } from "../dialogs";

const emit = defineEmits<{ (e: "entered", id: string, name: string, isNew: boolean): void }>();
const items = ref<Array<Record<string, unknown>>>([]);
const err = ref("");

async function load() {
  err.value = "";
  try {
    const r = await api.listProjects();
    if (r.ok) items.value = r.projects || [];
  } catch (e: any) {
    err.value = e?.message || String(e);
  }
}
function metaOf(p: Record<string, unknown>) {
  const s = t("proj_meta").replace("{c}", String(p.chars ?? 0)).replace("{b}", String(p.bgs ?? 0)).replace("{s}", String(p.shots ?? 0));
  const mod = String(p.modified || "");
  return s + (mod ? " · " + mod : "");
}
async function onNew() {
  const name = await uiPrompt(t("proj_name_prompt"), t("proj_default_name"));
  if (!name) return;
  const r = await api.createProject(name.trim());
  if (r.ok && r.id) emit("entered", r.id, name.trim(), true);
  else { await uiAlert(t("proj_new_fail").replace("{d}", r.detail || "unknown")); load(); }
}
function onOpen(p: Record<string, unknown>) {
  const id = String(p.id);
  emit("entered", id, String(p.name || id), false);
}
async function onDelete(p: Record<string, unknown>) {
  const ok = await uiConfirm(t("proj_del_confirm").replace("{n}", String(p.name || p.id)));
  if (!ok) return;
  const r = await api.deleteProject(String(p.id));
  if (!r.ok) await uiAlert(r.detail || "delete failed");
  load();
}
onMounted(load);
</script>

<style scoped>
.proj-home { padding: 28px 30px; display: flex; flex-direction: column; gap: 22px; height: 100%; overflow: auto; }
.ph-hero { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.ph-brand { display: flex; gap: 12px; align-items: center; color: var(--fg, #e8ecf4); }
.ph-brand svg { width: 34px; height: 34px; stroke: var(--accent, #4da3ff); }
.ph-title { font-size: 22px; font-weight: 700; letter-spacing: 1px; }
.ph-sub { font-size: 12px; color: var(--dim, #9aa3b6); margin-top: 2px; }
.ph-new {
  display: flex; align-items: center; gap: 10px; padding: 12px 22px; border: none; border-radius: 10px;
  background: var(--accent, #4da3ff); color: #fff; font-size: 15px; font-weight: 600; cursor: pointer;
  transition: filter .15s;
}
.ph-new:hover { filter: brightness(1.08); }
.ph-new-plus { font-size: 18px; }
.ph-recent-title { font-size: 13px; font-weight: 600; color: var(--dim, #9aa3b6); margin-bottom: 4px; }
.ph-empty { color: var(--dim, #9aa3b6); font-size: 13px; padding: 14px 4px; }
.ph-card {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  border: 1px solid var(--border, #263041); border-radius: 10px; padding: 12px 16px;
  background: var(--card, #1b2230); margin-bottom: 10px; transition: border-color .15s;
}
.ph-card:hover { border-color: var(--accent, #4da3ff); }
.ph-card-body { cursor: pointer; flex: 1; min-width: 0; }
.ph-card-name { font-size: 14px; font-weight: 600; color: var(--fg, #e8ecf4); }
.ph-card-meta { font-size: 12px; color: var(--dim, #9aa3b6); margin-top: 3px; }
.ph-card-actions { display: flex; gap: 8px; flex-shrink: 0; }
.ph-err { color: var(--danger, #ff6b6b); font-size: 12px; }
</style>
