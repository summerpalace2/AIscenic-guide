# CP4.4 R12 — `/trips` 登录恢复与列表加载闭环

Date: 2026-10-10  
Result: **R11 暴露的浏览器硬刷新卡在加载态问题已复现、定位并修复；隔离浏览器验证通过。**

## 根因

实际页面是 Vue 前端。`App.vue` 在挂载后异步调用 `authStore.restoreSession()`，而 `TripsPage.vue` 只在组件 `onMounted` 时检查一次 `authStore.user`。硬刷新时组件先挂载、用户仍为 `null`，因此不会执行 `loadTrips()`；认证恢复随后把用户写入 store，但页面没有监听账号变化，也不会补发列表请求。`savedTrips` 继续保持初始 `null`，模板于是永久显示“正在读取您的行程……”。登录后在同一页面也会遇到相同竞态。

复现时页面表现为登录成功但一直显示加载文案；直接请求 BFF 的列表接口并非故障原因。调试同时暴露项目 `frontend/src` 中存在被忽略的旧 `.js` 生成文件：Vite 默认会优先解析 `.js`，可能让它遮蔽受版本控制的 `.ts` 源码。开发配置已调整为 TypeScript 优先，避免此类本地旧产物再次隐藏修复。

## 修复

- `frontend/src/pages/TripsPage.vue` 改为立即监听 `authStore.user.id`：认证恢复或登录成功后自动加载列表；登出时清空列表并使先前请求失效。
- `frontend/src/app/stores/trip.ts` 的 `loadTrips()` 捕获账号 ID 与请求代次，只允许当前账号的最新响应提交；错误状态可见并提供“重新读取行程”入口，不再无提示地停留在无限加载态。
- `frontend/vite.config.js` 将 `.ts` 放在 `.js` 之前解析，防止 `src/**/*.js` 忽略产物遮蔽 TS 源码。

## 验证

- 隔离服务使用临时 Java SQLite、BFF JSON 和合成账号；不连接项目正式数据库、Redis、模型或地图供应商。
- **真实浏览器硬刷新** `/trips`：登录 cookie 恢复后，页面显示“已保存 1 份行程”及测试行程卡片，不再卡加载。
- **真实浏览器登出再登录**：账号变化触发新列表请求，同一张行程卡片重新出现。
- Vite production build（Vue 根配置）：成功，116 modules transformed。
- Node 前端/BFF 回归：141 passed，0 failed、0 cancelled、0 skipped。
- `vue-tsc --noEmit -p tsconfig.json` 未通过：报告 `ChatPanel.vue` 中 8 处已有候选字段类型错误（`isDining`、`type`、`specialtyDish`、`averageCost`、`costSummary`）；这些错误与本次修改无关。本轮未据此声称 TypeScript 全量检查通过。
- 测试服务已停止，18080、3000、15173 端口均已释放。

## CP4 状态

R11 中“硬刷新后行程列表卡住”的已知阻断项由本报告关闭。保存、打开、局部修改、保存、硬刷新流程现有 R11 与本报告的实际服务/浏览器证据。CP4 累计限制仍应按 R11 如实保留：未验证 Open 回执丢失且 Java session 补偿 DELETE 同时失败的孤儿会话场景，也未在 PostgreSQL、真实 Redis、多实例环境验证；不得把隔离环境证据扩写成这些环境均通过。
