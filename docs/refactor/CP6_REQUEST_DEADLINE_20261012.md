# 动态规划冷请求：供应商请求预算

## 已复现事实

首次真实冷请求曾在 BFF 60 秒时返回 503；Java 显式空间分支随后完成，dynamic 76.043 秒、总计 76.071 秒，文案 0 秒。证据由主线程保存在 BFF `docs/real-cold-plan-first-request-20261012.md`。本轮没有再次请求真实供应商，不能据离线通过声称真实冷请求验收通过。

## 修复范围

`PlannerService.createInternal` 在 prepare 解析之前打开一个单调时钟截止时间，配置 `planner.request-timeout-ms` / `PLANNER_REQUEST_TIMEOUT_MS`，默认 45000ms，低于现有 BFF 60000ms。没有提高 BFF 超时或增加写请求重试。

上下文只有 open、remainingNanos、expired、carry、close；嵌套作用域采用较早截止时间，close 恢复此前值。高德编排、HTTP、路线 hydration 与文案 executor 显式 carry，并在 finally 恢复线程上下文。

- 高德 HTTP 提交前、worker 开始前均检查预算；单次等待使用单次上限与请求剩余预算的较小值。已耗尽时不提交新调用、不继续重试或 fallback 请求。
- 高德服务自有 HTTP 池采用 AbortPolicy，提交被拒绝时返回不可用，不再使满队列请求直接在提交者线程执行不可控 HTTP；没有修改全局 ThreadPoolConfig。
- routePair 编排排队与两种路线共享剩余预算；路线批次、缓存防抖等待、文案等待同样受剩余预算约束。
- 缓存 follower 超时只结束自己的等待，不取消共享 owner Future。POI/天气缓存边界将等待异常归一化为领域 TIMEOUT，避免抛出未处理 500。
- RuntimeLocationPlanner 在候选循环与餐饮/路线调用前后检查预算；只保留已完成的站点，不把超时后的迟到结果加进计划。到期不搜索备用大范围结果、不继续加站。
- 保留非空结果时明确 `degraded=true`、`PLANNING_DEADLINE_EXCEEDED`、`PARTIAL_TIMEOUT` 以及用户可读的未完成 summary/sourceStatus；已核验路线仍保留其真实来源。
- 超时且无站点返回 504，不调用 repository.create，不创建空的成功会话，也不重新回退到全城计划。没有提前写入创建占位，因而该分支没有悬挂幂等 reservation。

## 实际离线验证

`./mvnw.cmd -pl ai test -Dtest=OperationDeadlineTest,RuntimePlanningDeadlineTest,RouteWaitBudgetTest,RuntimeLocationPlannerTest,AmapPlannerGatewayTest,AmapRouteServiceTest,GroundedNarrativeServiceTest,PlannerServiceTest,ColaArchitectureGuardTest`

20:31:52 完成：**105 项通过、0 失败、0 错误、0 跳过**，含 54 项架构约束。日志 `D:/scenic-guide/cp6-request-deadline-regression.log`。最后一轮同时覆盖简化后的自有 HTTP 池拒绝策略：填满 1 个工作线程和 256 个队列槽后请求返回 UNAVAILABLE，供应商 get 从未调用。

新增证据包括：忽略中断的慢 HTTP 仍在 120ms 请求预算附近返回且不重试；同 key follower 30ms 到期不取消 owner，owner 释放后仍成功且仅一次 HTTP；动态计划保留首站、下一站耗尽预算后停止并标识部分完成；空超时返回 504 且不调用 builder/repository.create；嵌套作用域与线程池复用无截止时间泄漏。第一轮新空超时测试因独立 parser 缺少目的地返回 400，补充显式目的地与天数后原 504/禁止 fallback/禁止保存断言通过，未削弱断言。

application.yml 已重新解析。未修改开发者环境文件、迁移代码、BFF 业务代码或真实用户数据。

## 限制与下一步

此次自动开启预算的是 **create** 路径，scope 位于创建幂等锁内部。等待该锁本身、repository 查询/保存及响应传输没有新增截止机制；shadow、replan、局部调整没有自动开启新的 45 秒 scope，继续沿用其既有行为。ThreadLocal 离开 create 后清理，不会影响下一次独立请求。

供应商取消不能保证已经发出去的 HTTP 在服务端停止执行；其 Future 结果会丢弃，没有异步代码写回行程或发布会话。缓存 follower 不可取消其他请求的工作。

BFF 并行 RAG 是独立请求，未被 Java 创建作用域覆盖；仍需要真实首次冷请求测量及最终全量回归。前端部分计划提示由主线程另行修复与验证。独立审查结论和主线程实测结果应追加，不得把本报告的离线通过替代完整端到端验收。

## 主线程后续验收追加

最终 Maven 全量 1183 run，1169 passed，14 skipped，0 failure/error；打包成功。独立静态复查未发现本轮确定阻断缺陷，见 CP6_REQUEST_DEADLINE_INDEPENDENT_REVIEW_20261012.md。首次真实冷规划返回 200；纠正真实测试过短的 10 秒确认等待后，另一全新隔离环境的规划、预览、应用、保存、恢复、继续删站一次通过（58.1 秒）。该次规划 28.355 秒，未触发 deadline marker，不能代替真实超时部分生成分支验收。详情和旧失败保留于 CP6_SEQUENTIAL_READINESS_20261012.md 及 BFF 对应实测报告。
