# CP6 架构门禁与可携带交付验收

日期：2026-10-12  
状态：完成本地冷检出、三库身份核对、静态硬编码复核及独立红队复查  
范围：Java 后端、Node BFF、Vue/legacy 前端当前工作树组合；不包含部署验收。

## 验收结论

CP6 的可携带交付与架构门禁已在最终候选中通过。最终候选目录为 `C:\Users\summer\AppData\Local\Temp\scenic-cp6-final-20261012-08`。Java 全量 Maven 测试、BFF/前端测试、BFF 架构门禁、Vue 类型检查、生产构建和依赖审计均通过。源码审计补出前端偏好生成器会自动把行程目的地写成重庆的问题；默认提示、时长模板和默认主题已经改为城市中立，并新增回归测试。

这不等于宣布整个产品已经实现全国城市零硬编码。Java 业务策略扫描未发现违规；BFF/前端运行时的城市默认、地图中心和提示词注入已清除。精选重庆 POI 目录仍包含静态坐标和城市化描述，这是产品数据资产，仍需在全国目录化时迁到外部数据源。本报告将其作为明确边界保留。

## 候选身份与复制清单

候选由三个源工作树各自的 `HEAD`、tracked patch 与未跟踪文件组成。tracked patch 与源目录 `git diff --binary HEAD` 字节一致；manifest 中每个文件的 SHA-256 均与源目录及候选相符。三库 `git diff --check` 与 `git fsck --full --no-reflogs --connectivity-only` 通过。完整身份和测试日志 SHA-256 见候选根目录 `cp6-test-attestation.json`。

| 仓库 | HEAD | tracked patch SHA-256 | 未跟踪源码文件 | manifest SHA-256 |
| --- | --- | --- | ---: | --- |
| Java `D:\scenic-guide` | `46b1653e81050e3cdd9f23506b327bc6676bcbd8` | `AC4245DD9CDC617D99B6361DAAE0FA8CD67D2F337373A0AA422725E83E87DAEB` | 292 | `47D2129A6568899D130AE6EFFF67FE717B16080944382AED661F2150D28EF60A` |
| BFF `D:\loop-p0-functional-inventory-1-home` | `cc585b0946f908c0dbfcbc4987e7423e0a9fd812` | `C3CE7C9735796D2437CAC4455CAB3D8F3363FAFA4A4733C242E8C518F80C8F99` | 3 | `F5C22AB35D079C8DD3F14F9ECFF23C1C65785D4B8D819AF3466A48BD22A9F90A` |
| 前端 `D:\loop-p0-functional-inventory-1-home\frontend` | `657e0c3fc9a791a6f37b8b8e9d43bb0b448eedb3` | `85CB82080E2C0CA3CA6CEE15832616FFDE9D448D7163095194A724AEDB40929E` | 3 | `CB0E4F799A4E07085A4D6A57AE8A64C39F055BCF042E0BA40A51373BB97F22BF` |

日志文件放在候选根目录，未混入前端源仓库的未跟踪源码清单。候选内由 `npm ci`、Maven 与 Vite 生成的依赖/构建目录不属于源码 patch 或 manifest；前端依赖由锁文件安装并通过审计。

## 最终冷检出验证

所有结果都来自上表绑定的候选代码。Java 日志在前端最终增量前生成；Java 候选、HEAD 和 patch 在后续冻结中保持不变。BFF 与前端使用 final-08 代码复验。

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| Java | `mvnw.cmd clean test -DfailIfNoTests=true`（`java/ai`，隔离 SQLite） | 1135 项，0 失败、0 错误、10 跳过；`BUILD SUCCESS` |
| Java 架构 | Maven 中的 `ColaArchitectureGuardTest` | 54 条守卫通过 |
| Java 硬编码 | `python ai/scripts/audit-planner-hardcoding.py` | 122 个生产规划/餐饮/搜索/聊天 Java 文件，13 类规则，0 违规；脚本明确说明静态规则不证明语义泛化 |
| BFF | `npm test` | 11 项通过，包括 RAG 城市上下文、Origin/CSRF、畸形 Cookie 行为 |
| BFF 架构 | `npm run test:architecture` | 通过；检查 13 个 server 模块和 package 入口 |
| BFF 语法 | 对全部 `server/*.mjs` 执行 `node --check` | 全部通过 |
| 前端行为 | `npm test` | 5 项通过，覆盖地图质心、无坐标输入以及默认偏好不注入目的地 |
| 前端类型 | `npm run typecheck`（`vue-tsc --noEmit`） | 通过 |
| 前端构建 | `npm run build` | Vite 生产构建通过，52 modules transformed |
| 依赖 | `npm audit` | 0 vulnerabilities |
| 仓库卫生 | 三库 `git diff --check`、`git fsck --full --no-reflogs --connectivity-only` | 全部通过 |

实际命令输出及日志摘要都记录在 `cp6-test-attestation.json`；其中每个日志都有 SHA-256，避免把旧输出误当成最终候选结果。

## 本轮修复与硬编码复核

- BFF RAG 查询只在请求提供已解析城市时传递 `city`；未解析时省略字段，不默认重庆。对应 `retrieval-adapter` 契约测试覆盖指定城市、未知城市与结构化上下文。
- 前端地图中心按有效站点坐标计算均值；无有效坐标时显示无位置状态，不创建重庆中心。导航 URL 只有在上下文给出城市时才附加城市参数。
- 前端默认示例、天数偏好生成器和默认主题不再自动写入重庆。天气说明改为依目的地查询；新增测试覆盖默认提示和各行程时长。
- 饮食偏好保留自由文本，不从前端默认类别中注入重庆火锅/甜品等分类。坏编码 Cookie 被当作无效值，不导致服务端 500。
- BFF 旧 package 命令曾引用不存在的脚本并产生空测试结果；现在 `npm test` 和 `npm run test:architecture` 指向实际脚本，测试真实报告 11 项。
- 前端原 `build` 只执行 JavaScript 语法检查；现在运行真实 Vite production build，并增加 `typecheck` 入口。

CP5 的 Java 全量硬编码审计见 `CP5_HARDCODE_AUDIT_20261011.md`：覆盖 345 个生产 Java 文件和 18 个主资源文件；14 个 JSON/JSONL 校验有效、0 未分类命中，针对重点代码的 122 文件/13 规则扫描为 0 违规。本轮把检查扩展到 BFF/前端，先后发现并修复 RAG 重庆默认、地图坐标回退和偏好提示词重庆默认。复核仍确认精选重庆 POI 目录含静态坐标及城市内容，品牌文案和显式重庆示例也仍存在；这些属于当前城市产品目录/展示内容，不是跨城市通用策略，但意味着全产品尚不能宣称“0 硬编码”或全国城市覆盖。若目标是全国化，下一步应把 POI、品牌和城市主题数据从 BFF/前端代码目录迁到外部数据资产，并定义城市目录装载与缺失策略。

## 独立复核与证据更正

独立红队先后发现最终候选与源工作树身份不一致、前端目的地默认值遗漏，以及最后一次验收引用了旧前端日志。每项都已在 final-08 上收口：重建当前三库候选；修复城市默认并加回归；将本轮真实测试日志放到候选根目录，移出前端 Git 工作树的生成日志，并重算 manifest/attestation。最终补丁逐字节匹配源目录，前端日志记录 5 项测试及本轮 Vite bundle，BFF/Java 日志摘要与报告一致。红队最终确认前端规划调用链没有自动注入重庆；剩余城市化内容属于品牌、精选 POI 和显式演示数据。

## 限制与回切

- 没有执行真实浏览器端到端、PostgreSQL、真实 Redis、多实例竞争、进程强杀恢复或真实模型/地图供应商请求；CP8 跨实例连续性仍未验收。
- 精选重庆 POI 目录继续使用静态地点数据；全国城市产品化需要将其迁移到可替换的数据资产。这个边界不能由本地静态检查或单元测试消除。
- 三个用户源工作树保持未提交；没有 commit、push、部署或服务重启。
- 回切可基于表中 HEAD 和候选 canonical `*-working-tree.patch`、manifest 重建；不涉及正式行程或用户数据。
