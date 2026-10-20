# CP0 当前工作区基线

采集时间：2026-10-09T17:54:38+08:00（Asia/Shanghai）

本文件记录 2026-10-09 重构启动时的只读工作区身份。工作区包含用户未提交修改；这些修改属于基线，后续任务不得清除或当成本轮新引入。Git HEAD 仅作参照，实际候选由 HEAD、diff hash 与修改/未跟踪文件 hash 共同识别。

| 仓库 | 根目录 | HEAD | 跟踪文件差异 SHA-256 | 改动/未跟踪条目 |
| --- | --- | --- | --- | --- |
| Java | `D:\scenic-guide` | `46b1653e81050e3cdd9f23506b327bc6676bcbd8` | `cb647c71f2e8f9ad7ac1c36a45100841745d9ef1dcad4bba0d20a712613b9fae` | 27 |
| BFF | `C:\Users\summer\Documents\Codex\2026-08-12\loop-p0-functional-inventory-1-home` | `6eb2a07a98fc55eb51c051b57934badddc828bd6` | `a4ef5ab04425b95b28a84ec1952b29b5fc89eae9bb7859eafab9a33ebb4fb694` | 11 |
| Frontend | `C:\Users\summer\Documents\Codex\2026-08-12\loop-p0-functional-inventory-1-home\frontend` | `6dd081da50078d39cd76337c018cd5cb4042dd18` | `e53f9d583a913c9010925bdb1f6ec0f803c3c65a86f03019bf0311650d512bea` | 13 |

逐文件清单与 SHA-256：[20261009-CP0基线.json](C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP0基线.json)。

只记录路径、状态与哈希，不将源码、配置、密钥或用户数据复制到报告。哈希可用于核对后续内容是否变化，不是备份；用户需要保留可恢复副本时应另行创建受控备份。

## 初始文件清单

### Java（27 项）
- ` M` `ai/src/main/java/com/ai/guide/adapter/planner/web/PlannerController.java` SHA-256 `197d1880459895e0dfc23eceb5b09d25731836e96dbd6b6da06e4bfe00588c06`
- ` M` `ai/src/main/java/com/ai/guide/application/planner/PlannerApplicationService.java` SHA-256 `4a54f5a63dd0e72afe2ac806eaf9469876182c031a572eaec86c987371432f45`
- ` M` `ai/src/main/java/com/ai/guide/application/planner/PlanningApplicationService.java` SHA-256 `7e1a97d569b7c1f49cc8a57ab34d2b863861c38706833e6b62e666a47fe01b96`
- ` M` `ai/src/main/java/com/ai/guide/common/config/KnowledgeDbConfig.java` SHA-256 `128ce9186bf53d308512388e2f68518de22ce2a375eaa5d51cfca2bc3600ff60`
- ` M` `ai/src/main/java/com/ai/guide/domain/memory/service/TravelMemoryService.java` SHA-256 `fb12d37ba3e200382c6e186fc52f5036c0a246d9e812063d69006ceccb902908`
- ` M` `ai/src/main/java/com/ai/guide/domain/planner/service/PendingIntentContinuations.java` SHA-256 `7d4e724a8dde444ca95ac99658daf247fccfbd4b76ae1e3c41e4ed4ae47bd4db`
- ` M` `ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java` SHA-256 `99fa6676d79bb1db205ddff5183a0330c741ac518fd7ed38c0c5f33dbb51c7cb`
- ` M` `ai/src/main/java/com/ai/guide/domain/planner/service/adjustment/AddStopAdjustmentStrategy.java` SHA-256 `52295a4493075a1c1866fdb9cdb9f2f383d83703e7bbed0d49a3b400dc8b54d1`
- ` M` `ai/src/main/java/com/ai/guide/domain/rag/service/KnowledgeDocumentService.java` SHA-256 `30a54e1455dc4b5768aba11e0b5bbacab2fbf30dd32d23565783601d06c8e664`
- ` M` `ai/src/test/java/com/ai/guide/domain/memory/service/MemoryPaginationRegressionTest.java` SHA-256 `b3bab2bd801fe3bb1b8e46563d06962bb4afd54f5182c2907e4846b79f3e3dd1`
- ` M` `ai/src/test/java/com/ai/guide/domain/memory/service/MemoryPromptLifecycleRegressionTest.java` SHA-256 `7e86d437caa5a0fc58ae8f9b633075b126a0df1eb1363d07363bb7e4760ea499`
- ` M` `ai/src/test/java/com/ai/guide/domain/memory/service/MemorySafetyBudgetRegressionTest.java` SHA-256 `7b15485882485f2cddf3d310699b930f9eee23eef9adb05733810dac2f78bc00`
- ` M` `ai/src/test/java/com/ai/guide/domain/memory/service/TravelMemoryServiceTest.java` SHA-256 `38f0d90603db0e0f9cd70eb1f1e54dc9ff621a716e8fbe860e024ff1b060496c`
- ` M` `ai/src/test/java/com/ai/guide/domain/planner/service/CombinedLogicRegressionTest.java` SHA-256 `f7f537c6d886a4605470f0df408c09210c8eb2380269269ccb8c5ebe12c0612e`
- ` M` `ai/src/test/java/com/ai/guide/domain/planner/service/DeepAuditRegressionTest.java` SHA-256 `c12c94cc46f9ad73d554ffe5896df661213c238f867e6132edc6bba86e355dff`
- `??` `ai/src/main/java/com/ai/guide/domain/memory/port/MemoryRepositoryPort.java` SHA-256 `54ce597367af347eba83d9d0b8bb3a0d42c5b26f6d6a206d72da6de7d4131b87`
- `??` `ai/src/main/java/com/ai/guide/domain/planner/model/AdjustmentPreview.java` SHA-256 `7439f9121d57b76699c296b5a071073889375f7b44c299041d9f9fd2b7572054`
- `??` `ai/src/main/java/com/ai/guide/domain/planner/model/RestoreDraftRequest.java` SHA-256 `4734095825085e68ab85da70ffa74a820c5ff2197884bd01fc131ea584ff67ed`
- `??` `ai/src/main/java/com/ai/guide/domain/planner/model/StopPlan.java` SHA-256 `2700881c242400ee05bc3b652a8db1d4ac1e33add3f9ca2b70c468ed4030935f`
- `??` `ai/src/main/java/com/ai/guide/infrastructure/memory/repository/JdbcMemoryRepository.java` SHA-256 `2162381d897f02f71000041d35568786bfb466acd876feda177f1ca21c8d1d9a`
- `??` `ai/src/test/java/com/ai/guide/domain/memory/port/MemoryRepositoryPortContractTest.java` SHA-256 `b5039de051b61230274306fb90f80fa8fffca241e4ead482a544c11a08a3c501`
- `??` `ai/src/test/java/com/ai/guide/domain/planner/model/StopPlanRoundTripTest.java` SHA-256 `5aafc6e469971b105068978c75dfca92910b2d3765b52075ed6f16461153bfdf`
- `??` `ai/src/test/java/com/ai/guide/domain/planner/service/PlannerDraftRestoreTest.java` SHA-256 `35ccb8c0692b6d60b8399c862a4efd58145406a3b9222b8a99e8b856542e01c8`
- `??` `docs/baseline/BUSINESS_INVARIANTS_SPEC.md` SHA-256 `ce41c8bbd79d776ed3d59896c5d58b92169bf00445f6e5312fd8a7ea1fd28cae`
- `??` `docs/baseline/REPOSITORIES_BASELINE_V1.md` SHA-256 `5c63be2cfe4db07c582a7f6775717552e43e9d96c4182bae92ccfcc10ec139fa`
- `??` `docs/refactor/COLA_REFACTOR_EXECUTION_PLAN.md` SHA-256 `3dbaefa90f37f549639c2319e4e4298b9809868c74a17c2610a3e05b67386ac2`
- `??` `docs/refactor/STEP1_STEP2_STEP3_ACCEPTANCE_REPORT.md` SHA-256 `662a095c21ed8a60c1d035d718e21065e94fb4ce7ddfa994c8b13c2d6c28c856`

### BFF（11 项）
- ` D` `proxy-snippet2.js` SHA-256 `fed9a8796f8ac96f3d2942b4b9051178b826a86b23bd745ac47336c96de7bb5d`
- ` M` `server/index.mjs` SHA-256 `b0e33b23e400c265764e6f37320db02696f3cb2f7bea6e0b4be6c90f84de71c2`
- ` M` `server/java-core-client.mjs` SHA-256 `da486e5e0c8c54136be10333a1c8647f341ca40668f9081a7005e3cb500418e4`
- ` M` `start-local.ps1` SHA-256 `1cf924bf834e2ed1b06e6164cca6b554b461994dbd881c415da0796ca9d88d38`
- `??` `STARTUP_GUIDE.md` SHA-256 `66588a821286c99a1a2b766874f92ac4f403b54074b1b8b453ed40d857c826c7`
- `??` `server/draft-restore-route.test.mjs` SHA-256 `cd1fe5dd90068baee035cf96dcf4d3500ac537ba0fea57e7b0fc78d3420e1143`
- `??` `一键启动.bat` SHA-256 `1ef486d03458fac682ac57c537d6c15d6e6db8f89e2d1c75da37658aa5999cec`
- `??` `一键安装依赖.bat` SHA-256 `8df9f3f94411ce666d62100ecec29d8da5f25b1dee39105d8a4b38ca7c2550f5`
- `??` `重庆市首届高校AI大模型创新应用大赛_渝游智策商业计划书.md` SHA-256 `74547149b5937493e7ae2d05bae2fed748b79ad5fbb51634cd14537991da86e7`
- `??` `重庆市首届高校AI大模型创新应用大赛_渝游智策项目计划书.pdf` SHA-256 `ba4d0b25db2ad8887bd39b0275c8efce6438fe13a93418c9427ac67ecb022a3e`
- `??` `项目商业计划书_互联网+评委版.md` SHA-256 `6cb86dd76ec89909c37b419afd2c69a1bebf601dc614fd057a31a828b74a7d27`

### Frontend（13 项）
- ` M` `web/app.js` SHA-256 `9dc868a6ec7b4e0de339e13ccc9a3594e0fafbd2ac41a7393fba2e853d6e1e09`
- ` M` `web/src/app-core/state.js` SHA-256 `bb7dd7fbb95c010dc1f27cb3fd23e368a8a751c8de3d22357aed761a5865a1f9`
- ` M` `web/src/app-core/user-store.js` SHA-256 `b2aebe7d6fc3a9ab3dcbc690485b5301a7db7544091293e6bed2bff8704e28a5`
- ` M` `web/src/entities/trip/trip-mutation.js` SHA-256 `389df9298ca27c2102019e55e490acd6f9b78db5d240a98f48dc76790e5da367`
- ` M` `web/src/features/auth/auth-service.js` SHA-256 `4ed496942d6f409ff2d22388ee67144c3b466a8d9ab477f0c725315ecf2e2eea`
- ` M` `web/src/features/trip-chat/chat-action-chips.test.mjs` SHA-256 `ffd549e93e8bd7ea3b20e4dc598c2455285fe3ff81b4e75dd521615a73eadcf7`
- ` M` `web/src/features/trip-chat/chat-action-handler.js` SHA-256 `627e65652e552f8f0608119db6ece0750d18612ea46825a0c47f12b3f681b32e`
- ` M` `web/src/features/trip-chat/chat-panel.js` SHA-256 `277d77bd7c77e4217874b9f7c12ca3a99f387bc75ac4884aa6e97d34e28dbd6c`
- ` M` `web/src/features/trip-chat/chat-service.js` SHA-256 `1579c9dca55eb4d05b473d77f25a720ae30211ac5736423b878267887bd37129`
- ` M` `web/src/features/trip-chat/proposal-availability.test.mjs` SHA-256 `d38cf390e8aacd313e4246ab104c36d56fbbd163c3d01374fc2c1b1016579e1b`
- ` M` `web/src/features/trip-save/save-service.js` SHA-256 `7b9b924506474bbb35fd22e019fe4dd5b0c7b336e90dc0869c258bfb8ee1f858`
- ` M` `web/src/pages/plan-page.js` SHA-256 `fd3301e7a33d639794a0334d57d0dd25cc2af0685e38c3c1c9a485f53d297cd5`
- `??` `web/src/features/trip-save/draft-backup-restore.test.mjs` SHA-256 `e914cf9800adfb270da7b95ca1666663039a67d12911058e59947108b9b77520`

## 目录边界

- Java 代码位于 `D:/scenic-guide/ai`，Maven 命令从该目录执行。
- BFF 项目根目录位于 `C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home`。
- 前端是独立仓库，位于 BFF 根目录下的 `frontend`；Vite 源码位于其中 `web`。
- 三库存在未提交修改。冻结审查候选时必须重新采集身份；仅记录 HEAD 不足以代表候选。

## 本次尚未执行

## 主智能体执行的基线回归

- Java 规划主链与架构守卫：132 项，失败 0、错误 0、跳过 0。使用本轮独立临时 SQLite；原始日志：[Java CP0 回归](C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP0-Java回归.log)。
- BFF + Frontend 指定测试目录：104 项，失败 0、跳过 0；BFF `node --check` 通过；Vite 构建通过，60 个模块。原始日志：[Node 回归](C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP0-Node回归.log)、[Frontend 构建](C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP0-Frontend构建.log)。
- 命令与隔离环境见 [执行计划第 6 节](COLA_REFACTOR_EXECUTION_PLAN.md#6-可执行验证入口与证据规范)。这组基线不等于完整后端套件、浏览器 E2E、真实供应商或多实例验收。

工作区身份与这次运行之间有可能发生文件变化；已重新核对三个 Git 仓库的 HEAD 与 tracked patch hash，CP1 起始候选逐路径哈希见[CP1 候选冻结记录](C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1起始候选.json)。

三项子智能体独立交付见[调用链图](CP0_CALLGRAPH.md)、[状态审查](CP0_STATE_AUDIT.md)、[红方测试矩阵审查](CP0_TEST_REDTEAM.md)。主智能体将它们与回归日志汇总至[CP0 验收矩阵](CP0_ACCEPTANCE_MATRIX.md)。CP0 裁定：**通过，可进入 CP1，附 V08/V09/V16 与未跟踪测试资产等范围门槛**。CP1 起始 tracked patch hash 与启动时基线一致；CP1 的代码候选审查须基于后续新采集的 hash。
