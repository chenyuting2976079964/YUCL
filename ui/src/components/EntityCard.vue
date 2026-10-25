<template>
  <div class="cb-card" :data-id="entity.id">
    <div class="cb-head">
      <input class="cb-name" v-model="entity.name" :placeholder="t('name_ph')">
      <button type="button" class="cb-del" @click="emit('removed', entity)">{{ t("del") }}</button>
    </div>
    <div class="cb-src">
      <button type="button" :class="{ on: entity.src === 'upload' }" @click="switchSrc('upload')">{{ t("upload_img") }}</button>
      <button type="button" :class="{ on: entity.src === 'prompt' }" @click="switchSrc('prompt')">{{ t("prompt") }}</button>
    </div>
    <div class="cb-body">
      <img v-if="entity.imgSrc" class="cb-img" :src="entity.imgSrc" referrerpolicy="no-referrer" @error="onImgErr">
      <div v-else class="cb-imgph">暂无图片<br>生成或上传</div>
      <div class="cb-prompt">
        <template v-if="entity.src === 'prompt'">
          <textarea v-model="entity.prompt" :placeholder="t('desc_ph')"></textarea>
          <button type="button" class="cb-gen" :disabled="busy" @click="genImage">
            {{ busy ? t("generating") : t("regenerate") }}
          </button>
        </template>
        <template v-else>
          <input ref="fileInp" type="file" class="cb-file" accept=".png,.jpg,.jpeg,.webp,.gif,.bmp" @change="upload">
          <div style="font-size:12px;color:var(--dim2);margin-top:5px">
            {{ (entity.path && !entity.url) ? t("selected") + "：" + entity.path.split(/[\\/]/).pop() : t("select_file") }}
          </div>
        </template>
      </div>
    </div>
    <div v-if="kind === 'chars'" class="cb-voice">
      <span class="cb-vlbl" title="该人物的台词用它朗读（作为「人物1」的镜头时生效）">音源</span>
      <select v-model="entity.voice" style="flex:1;font-size:12.5px;padding:5px 6px">
        <option value="">默认（跟随全局旁白声源）</option>
        <optgroup v-if="(options?.voices || []).length" label="系统音色">
          <option v-for="v in options!.voices" :key="v.value" :value="'tts:' + v.value">{{ v.label || v.value }}</option>
        </optgroup>
        <optgroup v-if="voices.length" label="我的语音文件">
          <option v-for="v in voices" :key="v.name" :value="'file:' + v.name">{{ v.name }}</option>
        </optgroup>
      </select>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { api, baseUrl, type Options, type VoiceItem } from "../api";
import { uiAlert } from "../dialogs";
import { t } from "../i18n";
import type { CbEntity } from "./CharBgForm.vue";

const props = defineProps<{
  entity: CbEntity;
  kind: "chars" | "bgs";
  options: Options | null;
  voices: VoiceItem[];
  provId: string;
  imgModel: string;
}>();
const emit = defineEmits<{ (e: "removed", ent: CbEntity): void }>();

const busy = ref(false);
const fileInp = ref<HTMLInputElement | null>(null);
// 项目打开/新建会整体替换实体对象：重置旧卡片残留的「生成中」等忙碌态
watch(() => props.entity, () => { busy.value = false; });

function switchSrc(src: "prompt" | "upload") {
  props.entity.src = src;
  // 只切换来源模式，不清空已有图片（生成/上传所得）：
  // 图片仅当用户「重新生成」(genImage) 或「真的上传了新文件」(upload) 时才被替换；
  // 旧行为点一下 tab 就清空 imgSrc/path/url/image → "一点上传图片，已生成的图就没了"
}
// 图片加载失败（本地文件被清 / 公网 URL 过期）→ 先试公网回退，仍失败清回占位符，不显示破图
function onImgErr() {
  const s = props.entity.imgSrc || "";
  if (s.indexOf("/images/preview?") !== -1 && props.entity.url) {
    props.entity.imgSrc = props.entity.url; // 本地预览失败 → 试一次公网
    return;
  }
  if (s) props.entity.imgSrc = "";
}
async function genImage() {
  if (!props.entity.prompt.trim()) { await uiAlert("请先填写提示词"); return; }
  busy.value = true;
  // 客户端 4 分钟兜底超时：服务端单端点最长 120s+60s，防止卡死在"生成中…"
  const ctl = new AbortController();
  const tid = setTimeout(() => ctl.abort(), 240000);
  try {
    const j = await api.genImage({
      prompt: props.entity.prompt, size: "1024x1024",
      model: props.imgModel, provider_id: props.provId
    }, ctl.signal);
    clearTimeout(tid);
    if (j.ok) {
      const base = j.local_path ? j.local_path.split(/[\\\/]/).pop() || "" : "";
      props.entity.path = j.local_path || "";
      props.entity.url = j.url || ""; // 保留公网地址（视频合成 AI 引擎引用用）
      props.entity.image = base;
      // 预览统一走本地 /images/preview（文件已落盘在 cache/images，后端已加该目录回退）；
      // 旧的 j.url（公网 CDN）在本机走系统代理常 0 字节 + 1h 过期 → "生成成功却看不到图"
      props.entity.imgSrc = base ? baseUrl + "/images/preview?name=" + encodeURIComponent(base) : (j.url || "");
    } else await uiAlert("生成失败：" + (j.error || j.detail || "未知错误"));
  } catch (ex: any) {
    clearTimeout(tid);
    await uiAlert("生成失败：" + (ex?.name === "AbortError" ? "超时（超过 4 分钟）" : ex?.message));
  }
  busy.value = false;
}
async function upload(e: Event) {
  const input = e.target as HTMLInputElement;
  const f = input.files?.[0];
  if (!f) return;
  try {
    const fd = new FormData();
    fd.append("file", f, f.name);
    const j = await api.uploadImage(fd);
    if (j.ok) {
      props.entity.path = j.path || "";
      props.entity.url = "";
      props.entity.imgSrc = baseUrl + "/images/preview?name=" + encodeURIComponent(j.name || "");
      props.entity.image = j.name || "";
    } else await uiAlert("上传失败：" + (j.detail || j.error || "未知错误"));
  } catch (ex: any) {
    await uiAlert("上传失败：" + (ex?.message || ex));
  }
  input.value = "";
}
</script>
