# CP4.4 R11 — 隔离 Java↔BFF 与真实页面集成验收

Date: 2026-10-10  
Result: **Java/BFF 补偿与持久化集成路径通过；浏览器硬刷新后的行程列表仍卡在加载态，CP4 不通过**

## 隔离环境

- Java 服务运行于 `127.0.0.1:18080`，使用本轮专用临时 SQLite、临时上传目录和合成账号；没有连接项目正式数据库、真实 Redis、模型或高德服务。
- BFF 运行于 `127.0.0.1:3000`，`YUYOUZHICE_MEMORY=0`，使用本轮临时 JSON 文件持久化；前端 Vite 运行于 `127.0.0.1:15173`。
- BFF health 显示 Java Core Backend 路由/POI/RAG 能力归属，Java stub 关闭。Java liveness/readiness 为 HTTP 200；聚合 `/actuator/health` 因隔离环境 Redis 不可达返回 503，因此本报告不宣称完整依赖健康。
- 所有业务数据均为合成数据。没有删除或回滚项目原有数据库内容。

## 实际验证

### 1. Java 与 BFF 的 Save/Open/Remove/Save/Open

真实 HTTP 请求经过 Java 与 BFF：

1. Java 注册合成用户并创建正式行程；
2. BFF 登录、打开正式行程并创建 Java planner session；
3. BFF 局部移除站点，planner session 版本变为 2；
4. BFF 保存正式行程，正式版本变为 2；
5. BFF 列表、重开后仍为该正式版本，且只含保留站点。

该流程使用真实服务进程和临时持久化，并非 mock/VM；不覆盖 PostgreSQL/Redis、多实例或供应商集成。

### 2. BFF 重启后的持久化

停止并重启使用相同临时 JSON 文件的 BFF 进程，重新登录后列表和 Open 仍返回正式版本 2 与预期站点。证明此 BFF JSON 存储在单进程重启后可恢复该业务快照；不代表多实例共享或 crash consistency。

### 3. Java Open session 补偿

- 删除未修改的 Open session：HTTP 200；重复删除仍 HTTP 200。
- 尝试删除已变更 session：HTTP 409；随后读取确认 session 仍存在。
- 故障注入：使 BFF 临时 JSON 原子替换失败。BFF Open 返回 HTTP 500；Java 随后查询不到刚创建的 session；日志包含 `planner-open-java-session-compensated`。

以上为实际 Java/BFF HTTP 故障路径，验证了远端 session 删除、已变更会话保护以及本地持久化失败后的补偿。不覆盖“响应在传输中丢失、BFF 未获 session ID”或补偿 DELETE 本身网络失败；这两种情况仍可能遗留 orphan。

### 4. 真实浏览器工作流

浏览器页面指向 Vite → BFF → Java，使用隔离合成账号完成：

`登录 → 我的行程 → 打开 → 移除测试站点（planner version 2）→ 保存（formal version 3）→ 再打开（无站点）`

页面反馈“已移除该站点”及“已更新正式行程”，行程库显示第 3 版；重新打开后地图机位数为 0，UI 与保存快照一致。另一次未修改保存后列表仍为第 2 版，未观察到空保存版本自增。

## 阻断 CP4 的实测问题

对上述已保存行程执行浏览器硬刷新：

- 刷新 `/planning` 显示“还没有行程草稿”；从“我的行程”导航后开始读取列表。
- 刷新 `/trips` 后页面持续显示“正在读取您的行程……”；等待超过前端 15 秒请求超时并重新点击导航后仍保持加载态。退出并重新登录后也仍保持该状态。
- 同一时段使用合成账号新登录，直接请求 BFF `GET /api/trips` 得到 HTTP 200、返回 1 条正式行程。故障仅在当前浏览器 UI 刷新路径上复现；尚未取得该浏览器请求的网络/控制台证据，不能确定根因，也不能把它归因到 Java/BFF 服务端。
- 这使 CP4 §3.5 的“保存→打开→局部修改→保存→重载”尚未闭环：列表/API 数据仍在，但页面硬刷新后无法显示它，必须先定位并修复/验证前端加载状态恢复。

## 既有自动化验证（承接 R10）

| Scope | Result |
|---|---:|
| Java application/controller/repository deletion/architecture tests | 43 passed, 0 failed/errors/skipped |
| BFF + scripts + frontend feature tests | 140 passed, 0 failed/cancelled/skipped |
| Temporary SQLite repository tests | Included; 2 passed |

本轮没有修改生产代码，因此没有重复运行这些自动化测试。`PlannerSessionRepositoryTest` 使用项目持久数据库配置，不在此处重跑。

## 裁决与下一步

- R11 的真实 Java↔BFF 正常流、BFF 重启持久化、Open session 补偿、浏览器正常局部修改保存均通过。
- **CP4 仍未通过，不得进入 CP5。** 阻断项是实际浏览器硬刷新后 `/trips` 加载态不结束。服务端直连 API 可用，但未证明浏览器页面恢复可用。
- 下一切片应只调查登录态恢复、`loadTrips` 请求所有权/超时 `finally` 清理和 BFF cookie 请求路径，先用最小复现定位，再修复并补充“刷新 `/trips` 后列表出现”浏览器回归。
- R10 已记录 Redis 不可达和历史测试污染处置；本轮沿用其测试隔离约束，不触碰持久项目数据库。
