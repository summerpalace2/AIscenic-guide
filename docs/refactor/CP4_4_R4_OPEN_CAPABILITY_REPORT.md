# CP4.4 R4 — Java Open capability contract

日期：2026-10-09  
状态：实现候选已冻结，等待 B/C 独立复审；不代表 CP4.4 或全链路验收。

## 缺陷与修复

架构复审确认 Java `PlannerOpenApplicationService` 用 `sessionRepository.create(...)` 创建新 session 后，只把 session snapshot、session ID 和版本放进 Open response，却遗漏 `CreateResult.accessToken()`。BFF 的 Open 路由需要该能力值，后续 session 操作会将其作为 `X-Plan-Session-Token` 发送；Open 因而无法建立完整可用的 BFF/Java session bridge。

本轮在 Java Open 成功响应顶层增加 `sessionAccessToken = created.accessToken()`。该值只出现在 Open 响应 envelope，不写入 formal Trip 或 `trip` snapshot。原有 USER owner 授权仍由 `tripService.get(actor.userId(), formalTripId)` 执行；session repository 仍保存 token hash，后续 session capability 验证逻辑未改。

BFF Open 保留 R3 行为：消费 Java Open 的单一快照，校验 `sessionId`、capability、formal Trip version、sync revision 与 trip source 字段一致；workspace/draft 持久化成功后才注册 live BFF session。BFF contract fixture 与 Java 应用响应字段一致，包括 `sessionAccessToken`。

## 测试与证据

Java 测试在隔离的全新副本执行，未在共享 `ai/target` 运行 Maven：

```powershell
./mvnw.cmd "-Dtest=PlannerOpenApplicationServiceTest,PlannerControllerTest,TripControllerTest,ProposalRepositoryPortContractTest,PlannerSaveConsistencyTest" test
```

结果：**51 tests，0 failures，0 errors，0 skipped；BUILD SUCCESS**。覆盖登录 USER Open 返回非空 `CreateResult.accessToken()`、token 不进入返回的 trip map、Java Planner Open 实际 HTTP JSON 顶层返回 capability、普通 Planner session GET 和 formal Trip GET JSON 不暴露 capability，以及 repository capability contract / 后续 session 验证回归。

BFF 显式 Node 命令：

```powershell
node --test server/formal-trip-bff-contract.test.mjs server/draft-restore-route.test.mjs server/planner-bridge-contract.test.mjs server/trip-delete-version.test.mjs
```

结果：**27 tests，27 pass，0 fail，0 skipped**。`node --check server/index.mjs`、`node --check server/formal-trip-bff-contract.test.mjs`、`git diff --check` 通过。日志见 `CP4_4_R4_MAVEN_TEST.log` 与 BFF 仓库的 `docs/refactor/CP4_4_R4_BFF_TEST.log`。

## 边界与风险

- Java 单测/MockMvc 验证应用服务和 HTTP JSON shape；BFF VM harness 验证当前路由代码和可观察副作用。没有对运行中 Java/BFF 服务做网络端到端测试。
- 如果 BFF workspace/draft 持久化失败，Open 远端调用已经创建了 Java session。本切片没有 Java 取消/回收 Open session 的协议，可能留下远端 orphan；不能视为已解决。
- Draft 写入失败后 BFF 尝试删除新 session 的 draft key。若底层 cleanup 同样无法持久化，可能残留磁盘记录；live BFF session 仍未注册。不会删除 formal Trip 已有 workspace，避免破坏历史 workspace。
- 若 workspace 写入成功但随后 draft 写入失败，workspace 记录可能保留；它没有可用 BFF session ID 入口。本轮不回滚或删除可能已有的 workspace，以免误删此前会话数据。
- 现有工作树有大量用户/前序 CP dirty 修改；候选 manifest 记录两仓 HEAD、基线 hash、当前 raw patch hash 与文件 hash。没有 stage 或 commit。

候选身份详见 `CP4_4_R4_CANDIDATE.json`，审查后再决定是否进入下一切片。
