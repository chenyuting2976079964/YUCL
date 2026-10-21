// =====================================================================
// 隐藏原生滚动条但保留完整滚动能力
//
// 背景：JavaFX WebEngine 不认任何 CSS 滚动条定制（::-webkit-scrollbar /
//   scrollbar-width / scrollbar-color 全部忽略），溢出时会画一条它自己的
//   亮色（白）滚动条，无法染色也无法用 CSS 隐藏。
//
// 做法：把滚动容器切成 overflow:hidden（overflow 为 hidden 时浏览器/引擎
//   一律不画滚动条，这是布局层行为，与 CSS 支持无关），再用 JS 还原
//   滚轮 + 键盘滚动。内部真正可滚动的子元素（textarea / pre / 下拉）
//   保持原生滚动，不被拦截。
//
// 用 MutationObserver 跟踪动态插入的 .sm-body / pre（设置弹窗、日志框
//   是 v-if 挂载的），幂等安装（dataset.hsc 标记）。
// =====================================================================
const SEL = ".col, .sm-body, pre";

const isEditable = (t: EventTarget | null): boolean => {
  if (!(t instanceof HTMLElement)) return false;
  const tag = t.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || t.isContentEditable;
};

// 从 e.target 向上（不含 el 自身）找内部可纵向滚动的子元素
function innerScrollable(el: HTMLElement, e: WheelEvent): boolean {
  let n: HTMLElement | null = e.target instanceof HTMLElement ? e.target : null;
  while (n && n !== el) {
    const oy = getComputedStyle(n).overflowY;
    if (/(auto|scroll)/.test(oy) && n.scrollHeight > n.clientHeight + 1) return true;
    n = n.parentElement;
  }
  return false;
};

const canScrollY = (el: HTMLElement) => el.scrollHeight > el.clientHeight + 1;

function install(el: HTMLElement) {
  if (el.dataset.hsc) return;
  el.dataset.hsc = "1";
  el.style.overflow = "hidden"; // 布局层隐藏滚动条（引擎无关）
  if (!el.tabIndex) el.tabIndex = 0; // 键盘可聚焦

  el.addEventListener(
    "wheel",
    (e) => {
      if (innerScrollable(el, e)) return; // 内部 textarea/pre 自己滚，别拦
      if (!canScrollY(el)) return; // 没溢出，放行
      e.preventDefault();
      el.scrollTop += e.deltaY;
      if (e.deltaX) el.scrollLeft += e.deltaX;
    },
    { passive: false }
  );

  el.addEventListener("keydown", (e) => {
    if (isEditable(e.target)) return; // 输入框里的按键交给输入框
    if (!canScrollY(el) && !el.scrollWidth) return;
    const step = Math.max(48, Math.round(el.clientHeight * 0.8));
    switch (e.key) {
      case "PageDown": el.scrollTop += step; e.preventDefault(); break;
      case "PageUp": el.scrollTop -= step; e.preventDefault(); break;
      case "Home": el.scrollTop = 0; e.preventDefault(); break;
      case "End": el.scrollTop = el.scrollHeight; e.preventDefault(); break;
      case "ArrowDown": el.scrollTop += 40; e.preventDefault(); break;
      case "ArrowUp": el.scrollTop -= 40; e.preventDefault(); break;
      case "ArrowRight": el.scrollLeft += 40; break;
      case "ArrowLeft": el.scrollLeft -= 40; break;
      default: return;
    }
  });
}

export function installHiddenScrollbars() {
  const installOne = (el: Element) => {
    if (el instanceof HTMLElement) install(el);
  };
  document.querySelectorAll(SEL).forEach(installOne);
  const io = new MutationObserver((muts) => {
    for (const m of muts) {
      m.addedNodes.forEach((n) => {
        if (!(n instanceof Element)) return;
        installOne(n);
        n.querySelectorAll(SEL).forEach(installOne);
      });
    }
  });
  io.observe(document.body, { childList: true, subtree: true });
}
