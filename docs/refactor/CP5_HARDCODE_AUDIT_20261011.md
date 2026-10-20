# CP5 全后端硬编码专项审计

日期：2026-10-11  
候选：CP5 全域重构统一验收候选，详见 `CP5_FINAL_ACCEPTANCE_REPORT_20261011.md`。

## 审计结论

在 CP5 所有实现完成后，执行了全后端生产 Java 与主资源目录扫描，并对规划/餐饮/搜索/聊天的高风险词汇再跑定向规则。最终没有发现规则覆盖范围内的未分类硬编码违规；两个扫描器均无命中，资源文件均可解析。

这一轮扩大范围后发现，上一份验收报告只覆盖 121 个重点 Java 文件，遗漏了 `BaiduSearchService` 中的百度网页搜索与 favicon URL，以及基础设施层百度语音端点的固定值。前者已改为领域端口和 infrastructure adapter；百度网页搜索、favicon、语音 ASR/TTS/token URL 现由 `application.yml` 的环境变量可覆盖配置提供。Amap Base URL 也由配置提供。测试 classpath 会以测试专用 `application.yml` 遮蔽主配置，已在测试配置中加入不可联网的 provider URL，避免 Spring 上下文缺键。

## 扫描范围和规则

全后端扫描：

```powershell
python ai/scripts/audit-cp5-backend-hardcoding.py
```

范围为全部 345 个 `src/main/java` production Java 文件及全部 18 个 `src/main/resources` 资产。脚本检查经纬度数值、城市/景点/人物和餐饮词汇、固定钟点、餐段正则词汇、业务默认值、餐饮分类与供应商 URL 归属；解析所有 JSON/JSONL。结果：14 个 JSON/JSONL 校验通过、0 个无效文件、0 项未分类命中。

规划/餐饮/搜索/聊天专项扫描：

```powershell
python ai/scripts/audit-planner-hardcoding.py
```

范围为 122 个相关 production Java 文件，覆盖 13 类规则：坐标、名字/噪声名单、固定餐饮时间、城市/地点、食物/品牌、内联餐饮默认值、固定钟点、POI 业务回退关键词、餐段正则、展示标签、候选上限与聊天附近 POI 查询词尾。结果 `violations: []`。

## 命中处置与资产分类

- 百度网页搜索 URL 原先由 `domain.search` 拼接，现通过 `WebSearchLinkPort` 调用 `BaiduWebSearchLinkAdapter`；业务层不再持有百度 URL 或 URL 编码逻辑。
- 百度语音 ASR/TTS/OAuth token URL 与网页搜索 URL 均放在 `application.yml`，并支持环境变量覆盖。Amap Web Service base URL 同样由配置提供。
- 仍可见的 provider URL 字面量仅在 infrastructure：`AmapWebServiceClient` 的协议 fallback，以及 PDF 中构造高德导航/地点搜索链接。这些是第三方协议端点，不属于 domain/application 业务规则；生产可通过 Amap 配置覆盖 Web Service base URL。
- `attractions.json` 的 41 条与 `dining_venues.json` 的 50 条经纬度是明确的人工维护 POI 种子记录，不是运行时 fallback 坐标。代码动态搜索仍以实时 provider 返回结果为运行数据。
- 11 个 `taxonomy/*.json` 资产存储可变业务词汇、分类、策略与搜索扩展词；主、测试 `dining_taxonomy.json` SHA-256 同为 `D7E653A967CB9A069E406ED701EDC9A27CC7B839A8EF3668B77FC62A332F7025`。
- `dining_brands.txt` 是遗留文件，production 无读取方，taxonomy README 说明其状态。

## 最终证据

- 全后端扫描：345 个 production Java、18 个资源；14 个 JSON/JSONL 通过解析；0 无效资产、0 未分类命中。
- 高风险专项扫描：122 个 Java 文件、13 类规则、0 违规。
- 41 条景点与 50 条餐饮坐标均归类为 POI 种子数据；provider 端点分别归类为配置项或基础设施协议链接。
- 全量 Maven clean test：1129 项，0 失败、0 错误、10 跳过，`BUILD SUCCESS`。
- `git diff --check` 通过。

## 未覆盖与限制

- 规则扫描不能证明任意语言理解、任意城市 POI 质量、路线可达性或输出准确；本轮未对所有城市和真实供应商做实网测试。
- 资源分类针对 `ai/src/main/resources`；不包含前端、Node BFF、部署仓库外资产和依赖库，也不声称全仓不存在所有形式的字符串常量。
- taxonomy 可按文件覆盖，尚未在真实运维环境验证关联资产原子发布、版本兼容和多实例同步。
- PostgreSQL、真实 Redis、多实例与进程崩溃恢复不在本次本地 SQLite 测试证据中。
