# 冷启动规划：路线等待预算修复

本轮修复两个已确认的无界等待，不宣称整个规划请求已经稳定在 BFF 60 秒内。没有调用真实收费供应商或重测真实冷请求。

## 修改

- AmapRouteService 的路线对等待共用截止时间，包含编排排队、步行与公交等待。预算按现有公交 v5/v3 各两次尝试计算：`4 * routeTimeoutMs + 500ms`，避免等待队列无上限。
- 编排队列拒绝任务时返回不可用，避免改为调用线程内运行而绕过等待预算。中断或超时取消该等待者拥有的任务；不会取消其他请求在请求缓存中共享的任务。
- AmapPlannerGateway 的路线批次等待共用一个截止时间，新增 `amap.web-service.hydration-route-timeout-ms`，环境变量 `AMAP_WEB_SERVICE_HYDRATION_ROUTE_TIMEOUT_MS`，默认 15000ms。超时结果保留未核验状态，不伪造真实路线；迟到任务只返回供应商结果，不能修改已返回的行程。
- 增加 build、dynamic、narrative、POI、weather、routes 阶段耗时日志，仅记录阶段与毫秒数，不记录请求全文或密钥。

## 离线验证

命令：`./mvnw.cmd -pl ai test -Dtest=RouteWaitBudgetTest,AmapPlannerGatewayTest,AmapRouteServiceTest`。

新增测试覆盖排队任务永不启动时路线对超时取消，以及三个路线段共用批次预算、返回未核验结果、取消任务迟到执行不能修改结果。最终 22 项通过、0 失败、0 错误、0 跳过，BUILD SUCCESS（20:06:48）。最终统计见 `D:/scenic-guide/cp6-route-wait-budget.log`。application.yml 已用 PyYAML 重新解析，新增环境变量映射正确。

## 仍需完成

本节记录第一阶段范围。真实动态分支随后复现 76 秒耗时，并继续实现创建请求的供应商统一预算；最新改动与离线验证见 `CP6_REQUEST_DEADLINE_20261012.md`，实际冷请求仍待主线程重测。

本轮属于小范围等待预算修复。POI 逐站/逐关键词、build 中逐最终相邻边调用、文案独立预算及 BFF RAG 等待仍未统一为一个端到端截止时间。hydrationExecutor 的 CallerRunsPolicy 也可能在提交阶段同步运行任务；本轮限制的是提交后批次等待，不能声称整个 hydrate 有 15 秒硬上限。已启动的 CompletableFuture 取消不保证中断底层 HTTP，但其结果不会再写回行程。

真实冷请求先前 503 的具体耗时阶段尚无证据，新增阶段日志用于下一次定位。线程池实际 application.yml 默认并发 8、队列 64；注解中的并发 2 仅是未提供配置时的回退，不能混淆。
