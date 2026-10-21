import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import { installHiddenScrollbars } from "./scrollfix";
import "./styles/app.css";

const app = createApp(App);
app.use(createPinia());
app.mount("#app");

// WebEngine 画不掉的白滚动条：把滚动容器切 overflow:hidden 并用 JS 还原滚动
installHiddenScrollbars();
