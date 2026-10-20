<template>
  <Teleport to="body">
    <div v-if="dialog.open" class="dlgov" @click.self="onOverlayClick">
      <div class="dlgbox" ref="box">
        <div class="dlgmsg">{{ dialog.message }}</div>
        <input v-if="dialog.kind === 'prompt'" ref="inp" v-model="val" class="dlginput" placeholder="请输入"
               @keyup.enter="onOk" @keyup.esc="onNo" />
        <div class="dlgbtns">
          <button v-if="dialog.kind !== 'alert'" type="button" class="chipbtn" @click="onNo">取消</button>
          <button type="button" class="chipbtn" style="border-color:var(--accent);color:var(--accent)" @click="onOk">
            {{ dialog.kind === "confirm" ? "确认" : dialog.kind === "prompt" ? "提交" : "好的" }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { closeDialog, dialog } from "../dialogs";

const inp = ref<HTMLInputElement | null>(null);
const box = ref<HTMLElement | null>(null);
const val = ref("");

watch(() => dialog.open, async v => {
  if (v) {
    val.value = dialog.default;
    await nextTick();
    (inp.value || box.value?.querySelector("#dlg-ok") || inp.value)?.focus?.();
    inp.value?.select?.();
  }
});

function onOk() {
  if (dialog.kind === "prompt") closeDialog(val.value);
  else if (dialog.kind === "confirm") closeDialog(true);
  else closeDialog(undefined);
}
function onNo() {
  if (dialog.kind === "prompt") closeDialog(null);
  else if (dialog.kind === "confirm") closeDialog(false);
}
function onOverlayClick() {
  if (dialog.kind === "confirm") closeDialog(false);
}

let keyH: ((e: KeyboardEvent) => void) | null = null;
onMounted(() => {
  keyH = (e: KeyboardEvent) => {
    if (!dialog.open) return;
    if (dialog.kind === "alert" && e.key === "Enter") closeDialog(undefined);
    else if (dialog.kind === "confirm" && e.key === "Enter") closeDialog(true);
    else if (dialog.kind === "confirm" && e.key === "Escape") closeDialog(false);
    else if (dialog.kind === "prompt" && e.key === "Escape") closeDialog(null);
  };
  document.addEventListener("keydown", keyH);
});
onUnmounted(() => {
  if (keyH) document.removeEventListener("keydown", keyH);
});
</script>
