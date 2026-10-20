import { reactive } from "vue";

// 页内对话框（confirm/alert/prompt）
// 注意：JavaFX WebView 会静默吞掉原生 confirm/alert/prompt，必须用页内弹层。
export type DialogKind = "confirm" | "alert" | "prompt";

export interface DialogState {
  open: boolean;
  kind: DialogKind;
  message: string;
  default: string;
  resolve: ((v: any) => void) | null;
}

export const dialog = reactive<DialogState>({
  open: false, kind: "alert", message: "", default: "", resolve: null
});

function ask(kind: DialogKind, message: string, def = ""): Promise<any> {
  return new Promise(res => {
    dialog.open = true;
    dialog.kind = kind;
    dialog.message = String(message);
    dialog.default = def;
    dialog.resolve = res;
  });
}

export function closeDialog(value: any) {
  const r = dialog.resolve;
  dialog.open = false;
  dialog.resolve = null;
  if (r) r(value);
}

export const uiConfirm = (m: string) => ask("confirm", m);
export const uiAlert = (m: string) => ask("alert", m);
export const uiPrompt = (m: string, d = "") => ask("prompt", m, d);
