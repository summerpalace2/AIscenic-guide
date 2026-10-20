# 《渝游智策》COLA 重构方案：步骤 1、步骤 2、步骤 3 验收交付报告（校准与闭环版）

> **报告日期**：2026-10-07  
> **重构分支**：`refactor/plan-adjustment-monolith-decoupling`  
> **执行阶段**：步骤 1（核对最新基线）+ 步骤 2（固定业务契约）+ 步骤 3（两个代表性试点：加站强类型化 + 记忆服务仓储端口化，含复审问题彻底闭环）

---

## 一、验收结论：【前三步缺陷已彻底闭环，验收通过 (PASS)】

依据 [《COLA 重构优化方案与辅助智能体验收规范》](file:///D:/scenic-guide/docs/COLA_REFACTOR_OPTIMIZATION_PLAN.md) 及针对前三步复审指出的 4 项核心缺陷，本轮整改杜绝粉饰、杜绝扩大，对代码和测试进行了严谨的针对性修复与边界加固：

| 阶段 / 任务 | 目标要求 | 交付产物与依据 | 自动化验收结果 | 结论 |
| :--- | :--- | :--- | :--- | :---: |
| **步骤 1：核对最新基线** | 固化三库基线版本、真实测试套件状态、校准技术版本与未验证清单 | [REPOSITORIES_BASELINE_V1.md](file:///D:/scenic-guide/docs/baseline/REPOSITORIES_BASELINE_V1.md) | Java 949 项（939 通过、10 跳过、0 失败）、BFF 14 项全通、前端 41 项全通，POM 版本对齐 | **通过** |
| **步骤 2：固定业务契约** | 形式化定义 7 大核心业务不变量，校准 TTL（15分钟）与状态生命周期映射 | [BUSINESS_INVARIANTS_SPEC.md](file:///D:/scenic-guide/docs/baseline/BUSINESS_INVARIANTS_SPEC.md) | 覆盖预览、确认、取消、追问、版本单调、身份认领、物理极限，锚定真实测试方法 | **通过** |
| **步骤 3 试点一：加站强类型化** | 提取 `StopPlan` / `AdjustmentPreview` 值对象，实现 100% 无损往返、核心权威清洗与边界校验 | [StopPlan.java](file:///D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/model/StopPlan.java)<br>[AdjustmentPreview.java](file:///D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/model/AdjustmentPreview.java)<br>[StopPlanRoundTripTest.java](file:///D:/scenic-guide/ai/src/test/java/com/ai/guide/domain/planner/model/StopPlanRoundTripTest.java)<br>[AddStopAdjustmentStrategy.java](file:///D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/planner/service/adjustment/AddStopAdjustmentStrategy.java) | 8/8 往返与反例单测通过，多站/单站/餐饮加站全接入，null/false/空字符串/数值 100% 保留，非法值拒绝 | **通过** |
| **步骤 3 试点二：记忆仓储端口化** | 将 `TravelMemoryService` 内直接 SQL 抽离为领域端口，修复仓储事务自动装配与真实回滚 | [MemoryRepositoryPort.java](file:///D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/memory/port/MemoryRepositoryPort.java)<br>[JdbcMemoryRepository.java](file:///D:/scenic-guide/ai/src/main/java/com/ai/guide/infrastructure/memory/repository/JdbcMemoryRepository.java)<br>[MemoryRepositoryPortContractTest.java](file:///D:/scenic-guide/ai/src/test/java/com/ai/guide/domain/memory/port/MemoryRepositoryPortContractTest.java)<br>[TravelMemoryService.java](file:///D:/scenic-guide/ai/src/main/java/com/ai/guide/domain/memory/service/TravelMemoryService.java) | 契约测试 4/4 通过（含单参事务回滚与画像失败候选回滚），记忆域回归 27/27 全通，Service 0 `JdbcTemplate` 引用 | **通过** |
| **架构门禁与全量回归** | COLA 架构看门狗零违规，全量单测 0 失败 | ArchUnit 看门狗 + 全量 Maven 运行 | `ColaArchitectureGuardTest` 6/6 通过<br>全量 949 项测试通过（939 通过，10 跳过，0 失败，0 错误） | **通过** |

---

## 二、复审核心问题针对性整改详情

### 1. 记忆仓储事务回归修复（针对复审问题 1）
- **根因分析**：重构将 `JdbcTemplate` 外移至 `JdbcMemoryRepository` 时，一参构造器将 `transactions` 设为 `null`，导致测试或默认构造下 `runInTransaction` 直接执行无事务包裹的 `action.get()`。在画像更新失败（如 CAS 冲突）时，已更新的候选状态 `CONFIRMED` 和已写入的记忆无法回滚。
- **整改落地**：
  1. 在 `JdbcMemoryRepository.java` 构造器中加入自动兜底逻辑：若未显式注入 `TransactionTemplate`，但 `jdbc.getDataSource() != null`，则自动创建绑定该数据源的 `new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))`；
  2. 在 `MemoryRepositoryPortContractTest.java` 中新增两项回滚测试：
     - `testTransactionRollbackOnException`：直接验证 `port.runInTransaction` 内部抛异常时数据库写操作全部原子回滚；
     - `testTravelMemoryServiceConfirmCandidateRollbackOnPreferenceFailure`：模拟画像服务异常，验证端到端 `confirmCandidate` 失败后候选仍然为 `PENDING`，写入记忆为 0，支持用户合法重试。

### 2. 类型兼容实现真正的“100% 无损往返”（针对复审问题 2）
- **根因分析**：旧版 `StopPlan` 和 `AdjustmentPreview` 在 `toMap()` 中仅对非空、非 blank 字符串或 `isPinned == true` 写出，导致原 Map 中的显式 `null`、`""`、`false` 被静默丢弃；且核心数值字段（如 `id: 123`、`duration: 60`）被强制转为字符串，破坏了原 Wire 数据结构。
- **整改落地**：
  1. 在 `StopPlan` 与 `AdjustmentPreview` 中引入 `wireCoreValues` 契约快照机制：在 `fromMap` 时精准捕获所有核心键的原始类型与存在性（包括 `Integer`, `null`, `false`, `""`）；
  2. 在 `toMap()` 时无损还原原始 Wire 键值与类型，完全杜绝结构改变与类型强转；
  3. 在 `StopPlanRoundTripTest.java` 中新增 `testWireNullEmptyFalseAndNumbersLossless` 和 `testAdjustmentPreviewRoundTrip`，对 `id: 123`、`duration: 60`、`time: null`、`isPinned: false`、`location: ""` 做强类型往返断言。

### 3. 核心字段绝对权威与保留键清洗（针对复审问题 3）
- **根因分析**：旧版公开 record 构造器允许直接传入包含核心键的 `extras`；当核心字段为 `null` 或 `false` 时，`toMap()` 优先写出 `extras` 中的值，导致恶意/非法 extras（如 `isPinned: true`、`time: "25:99"`）渗透回输出。
- **整改落地**：
  1. 在 `StopPlan` 和 `AdjustmentPreview` 的规范构造器中强制清洗 `extras`，剔除所有 `CORE_FIELD_KEYS`，确保 `extras` 容器只包含真正的未知业务扩展属性；
  2. 增强领域值不变量守卫：
     - 校验 `id` 与 `optionId` 不能为空或空白；
     - 校验 `time` / `startTime` 必须符合合法时间格式，拒绝 `"25:99"`、`"24:00"` 等非法时间；
     - 校验 `duration` 不能为负数；
     - 校验 `location` 必须落在合法经纬度范围 `[-180, 180]` 与 `[-90, 90]` 内，拒绝 `"999,999"`；
     - 在 `fromMap` 缺失稳定 ID 时，由转换层生成唯一且不碰撞的 ID，避免多个站点共享占位 ID。
  3. 在 `StopPlanRoundTripTest.java` 中新增冲突注入测试与边界拒绝测试。

### 4. 扩大强类型化试点范围与校准报告描述（针对复审问题 4）
- **整改落地**：
  1. **试点一范围扩展**：在 `AddStopAdjustmentStrategy.java` 中，不仅多站动线分支，而且单站添加（line 562 `itineraryBuilder.createStop`）与餐饮插槽加站（line 740 `itineraryBuilder.createDiningStop`）全部分支的站点创建与候选方案均全面接入 `StopPlan` 与 `AdjustmentPreview`；
  2. **“0 JDBC”范围精确校准**：明确报告中的“0 JDBC”当前精确指代领域层 `TravelMemoryService` 记忆服务完成彻底解耦，不夸大为全领域层已消除 JDBC（其他领域模块将在后续步骤 4–7 中分阶段推进）；
  3. **基线文档校准**：
     - 技术栈校准为：Java 17 编译目标（JDK 23 环境）、Spring Boot 3.4.1、Spring AI 1.0.0-M3；
     - BFF 契约测试校准为：实测 14 项通过（涵盖 4 个测试套件）；
     - 提案 TTL 校准为：实测 15 分钟（`ProposalStore.DEFAULT_TTL_MS = 15 * 60 * 1000L`）；
     - 明确跳过的 10 项测试原因：缺少外部 `production-v2-source` 数据资产与真实 Qdrant 远程集群。

---

## 三、验证记录与实测日志

### 1. 记忆域套件测试实测（含回滚测试）
```bash
.\mvnw.cmd test "-Dtest=MemoryRepositoryPortContractTest,TravelMemoryServiceTest,MemoryPaginationRegressionTest,MemoryPromptLifecycleRegressionTest,MemorySafetyBudgetRegressionTest" "-DKNOWLEDGE_DB_URL=jdbc:sqlite:target/isolated-test-knowledge.db"
```
**实测结果**：
```
[INFO] Running S2 试点：MemoryRepositoryPort 独立仓储端口持久化契约测试
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.980 s
[INFO] Running com.ai.guide.domain.memory.service.MemoryPaginationRegressionTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.031 s
[INFO] Running com.ai.guide.domain.memory.service.MemoryPromptLifecycleRegressionTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.131 s
[INFO] Running com.ai.guide.domain.memory.service.MemorySafetyBudgetRegressionTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.537 s
[INFO] Running com.ai.guide.domain.memory.service.TravelMemoryServiceTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.125 s
[INFO] 
[INFO] Results:
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 2. 规划强类型化与规划调整策略测试实测
```bash
.\mvnw.cmd test "-Dtest=StopPlanRoundTripTest,PlanAdjustmentServiceTest" "-DKNOWLEDGE_DB_URL=jdbc:sqlite:target/isolated-test-knowledge.db"
```
**实测结果**：
```
[INFO] Running S1 试点：StopPlan 与 AdjustmentPreview 强类型模型、边界校验与 100% 无损往返契约测试
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.076 s
[INFO] Running com.ai.guide.domain.planner.service.PlanAdjustmentServiceTest
[INFO] Tests run: 66, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 16.20 s
[INFO] 
[INFO] Results:
[INFO] Tests run: 74, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 3. COLA 4.0 架构看门狗测试实测
```bash
.\mvnw.cmd test "-Dtest=ColaArchitectureGuardTest" "-DKNOWLEDGE_DB_URL=jdbc:sqlite:target/isolated-test-knowledge.db"
```
**实测结果**：
```
[INFO] Running com.ai.guide.architecture.ColaArchitectureGuardTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.932 s
[INFO] BUILD SUCCESS
```

### 4. 全代码库硬编码门禁扫描
```bash
python ai/scripts/audit-planner-hardcoding.py
```
**实测结果**：
```json
{
  "scope": "production planner and dining Java",
  "files": 99,
  "checks": [
    "coordinate_literal",
    "named_person_or_noise_list",
    "fixed_meal_insertion",
    "fixed_meal_switch"
  ],
  "violations": []
}
```

### 5. 后端全量测试回归实测
```bash
.\mvnw.cmd test "-DKNOWLEDGE_DB_URL=jdbc:sqlite:target/isolated-test-knowledge.db"
```
**实测结果**：
```
[INFO] Results:
[WARNING] Tests run: 949, Failures: 0, Errors: 0, Skipped: 10
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time: 57.735 s
```

### 6. BFF 契约测试实测
```bash
node --test (Get-ChildItem server -Filter '*.test.mjs').FullName
```
**实测结果**：
```
ℹ tests 14
ℹ pass 14
ℹ fail 0
ℹ duration_ms 86.9102
```

---

## 四、下一步计划（步骤 4 准备工作）

前三步骤经复审整改后已达到严格的质量要求：
1. **强类型试点**：`StopPlan` 与 `AdjustmentPreview` 实现了对弱类型 Map 的 100% 无损替代，核心字段绝对权威且具备输入边界防护，多站/单站/餐饮加站路径全面覆盖；
2. **仓储端口试点**：`TravelMemoryService` 彻底剥离了直接数据库操作，`JdbcMemoryRepository` 具备了健全的事务回滚保护；
3. **架构与契约**：COLA 分层守护通过，全量 949 项测试零失败。

请用户裁决，若确认前三步已无遗留问题，即可按计划进入：
- **步骤 4：收口提案与外部能力**
  - 提取提案仓储端口（`ProposalRepositoryPort`），收口 Caffeine + Redis 缓存与分布式 Epoch；
  - 提取大模型意图提取端口与向量检索端口；
  - 明确并发、失败、超时及重启后旧提案失效的权威状态机。
