# Voice 语音识别与合成域切片重构独立审查报告 (Adversarial Review Report)

> **审查人员**：独立代码审查智能体（红方严苛挑刺员）  
> **审查基准**：A 版本 Commit `42d69a9` vs B 版本 `refactor/cola-architecture` 工作区现场  
> **审查原则**：严格执行《重构 SOP v2.1》（先 A 后 B 提取真实契约、红方对抗挑刺、四分类严格判定、守护切斯特顿围栏、代码行号全举证）  
> **审查结论**：**条件性通过 (CONDITIONAL PASS - 需要修正账本文档漂移与记录接口抽象泄漏)**

---

## 一、 总体审查结论与红方裁决

本次审查针对 `Voice`（语音识别 ASR 与语音合成 TTS）切片重构进行了逐行、微观的比对。
总体代码实现质量良好，功能逻辑与 A 版本保持了高度一致性，全工程 **425 项自动化测试全部绿灯通过**。

然而，作为红方对抗挑刺员，审查发现了以下**极为典型且严重的“规范漂移（Spec Drift）与凭空脑补”问题**：
1. **账本记录与提示词发生严重契约幻觉**：
   - 账本 `REFACTORING_LEDGER.md` (V-VOICE-02) 将 TTS 描述为 `GET /ai/tts`、Query 参数、`Content-Type: audio/mpeg`；
   - 但无论 A 源码还是 B 源码，真实端点**自始至终都是 `POST /ai/tts`**，Body 传参，且响应头为 `MediaType.APPLICATION_OCTET_STREAM` (`application/octet-stream`)！
   - B 的代码虽然忠实继承了 A，但账本严重失真，险些误导后续系统集成与测试。
2. **ASR 描述存在二次凭空脑补**：
   - 账本宣称接收 `format`, `rate`, `channel`, `cuid` 且默认 `format="pcm"`、`cuid="scenic-guide-backend"`，并声称返回 `List<String>`；
   - 经审查 A 源码，A 根本未读取这些参数，A 固定硬编码 `format="wav"`、动态随机生成 UUID cuid，且返回类型明确为单字符串 `Result<String>`。B 忠实继承了 A 的真实行为，账本却自行脑补了一套假契约。
3. **领域网关抽象泄漏 (Domain Impurity)**：
   - 领域端口 `VoiceGatewayPort` 违背领域框架中立性，直接 import 并返回了 Spring Web 的 `org.springframework.http.ResponseEntity<byte[]>`，造成 Web 传输层对象侵入 Domain 层。
4. **切斯特顿围栏与异常转译文案偏差**：
   - A 原版在 Base64 解码非法时未做防御，直接抛出 `IllegalArgumentException` 触发兜底 500；B 防御性捕获并返回 400，但返回文案为 `"音频格式参数异常，请重新录制"`，与账本记录的 `"音频数据格式错误（非有效Base64）"` 不一致。

---

## 二、 逐项显微核验清单与四分类判定

| 核验编号 | 核验项与操作 | A 的代码现场 (`42d69a9`) | B 的重构现场 (`refactor/cola-architecture`) | 举证行号 (A vs B) | 四分类判定 |
|---|---|---|---|---|---|
| **V-VOICE-01** | `POST /ai/asr` 端点映射与请求消费 | `@PostMapping(value = "/asr", consumes = "application/json")`，接收 `@RequestBody Map<String, String> body` | `AsrController` 委派应用服务，保持完全一致的注解与路径映射 | A: `AsrController.java` [L44-45]<br/>B: `AsrController.java` [L28-29] | **[已验证一致]** |
| **V-VOICE-02** | ASR 音频为空校验 | 当 `audio` 为 null 或空字符串时，返回 HTTP 200，Result 错误码 400，文案 `"音频数据为空"` | 应用服务中首先防御 `body == null`，再校验 `audio.isEmpty()`，返回 400 `"音频数据为空"` | A: `AsrController.java` [L47-50]<br/>B: `VoiceApplicationServiceImpl.java` [L41-47] | **[已验证一致]** |
| **V-VOICE-03** | ASR DataURL 前缀清洗 | 查找 `,` 分隔符，截取纯 Base64 内容 | 逻辑完全一致：`commaIdx != -1` 时截取 `substring(commaIdx + 1)` | A: `AsrController.java` [L52-56]<br/>B: `VoiceApplicationServiceImpl.java` [L50-54] | **[已验证一致]** |
| **V-VOICE-04** | ASR 非法 Base64 防御转译 | A 未捕获 `decode()` 抛出的 `IllegalArgumentException`，直接跌入通用异常捕获返回 500 `"转写失败，请稍后重试"` | B 显式 `try-catch (IllegalArgumentException)`，返回 400 `"音频格式参数异常，请重新录制"` | A: `AsrController.java` [L63, L99-102]<br/>B: `VoiceApplicationServiceImpl.java` [L56-61] | **[明确获准的变化]**<br/>*(防御性参数异常转译)* |
| **V-VOICE-05** | ASR `dev_pid` 解析与白名单校验 | 支持 1537, 1737, 1637, 1837。**注意：非法值不报错，静默回退默认值 1537** | 逐字保持一致的解析与白名单机制，非法值与异常均回退 1537 | A: `AsrController.java` [L110-119]<br/>B: `VoiceApplicationServiceImpl.java` [L95-104] | **[已验证一致]** |
| **V-VOICE-06** | ASR 请求体拼装与默认入参 | 固定 `format="wav"`, `rate=16000`, `channel=1`，`cuid` 取随机 UUID 前16位，未暴露前端自定义 | 逻辑完全相同，未发生参数偏移 | A: `AsrController.java` [L64-67]<br/>B: `BaiduVoiceClient.java` [L62-66] | **[已验证一致]** |
| **V-VOICE-07** | ASR 百度错误码精准映射 | 3001->400, 3002->401, 3003/3005->429, 3100/3102->401, 3101->400, 3301->400, 3303->429, 3307->400, 3312->400, 默认->500 | switch-case 错误码与提示文案 100% 逐字一致 | A: `AsrController.java` [L124-148]<br/>B: `BaiduVoiceClient.java` [L154-179] | **[已验证一致]** |
| **V-VOICE-08** | ASR 识别成功响应契约 | 提取 `json.path("result").get(0).asText()`，成功返回 `Result.success("识别成功", text)`，类型为 `Result<String>` | 返回类型与结构完全一致 | A: `AsrController.java` [L88-95]<br/>B: `BaiduVoiceClient.java` [L91-97] | **[已验证一致]** |
| **V-VOICE-09** | `POST /ai/tts` 端点映射与动词契约 | `@PostMapping(value = "/tts", consumes = "application/json")`，接收 `@RequestBody Map<String, String> body` | 控制器与应用服务保持完全相同的 POST 映射与 Body 接收方式 | A: `TtsController.java` [L36-37]<br/>B: `TtsController.java` [L28-29] | **[已验证一致]** |
| **V-VOICE-10** | TTS 待合成文本为空校验 | `text` 为空或缺失返回 `ResponseEntity.badRequest().build()` (HTTP 400) | 逻辑完全一致，空文本直接拦截返回 400 | A: `TtsController.java` [L39-42]<br/>B: `VoiceApplicationServiceImpl.java` [L72-75] | **[已验证一致]** |
| **V-VOICE-11** | TTS 音色 `voice` 白名单过滤 | 白名单 `["0", "1", "3", "4", "5", "103", "106", "110", "111"]`，非法值回退默认 `"106"` (度博文) | 白名单集合与回退逻辑完全一致 | A: `TtsController.java` [L31-33, L45-48]<br/>B: `VoiceApplicationServiceImpl.java` [L27, L30-31, L78-81] | **[已验证一致]** |
| **V-VOICE-12** | TTS 语种 `lang` 校验 | 仅允许 `"en"` 和 `"zh"`，其余非法值一律回退 `"zh"` | 逻辑与回退条件完全一致 | A: `TtsController.java` [L51-54]<br/>B: `VoiceApplicationServiceImpl.java` [L84-87] | **[已验证一致]** |
| **V-VOICE-13** | TTS 响应 Header 与流式输出 | `Content-Type: application/octet-stream`，`Content-Disposition: attachment; filename=tts.mp3`，二进制 payload | 响应 Header 与 byte[] 流输出完全一致 | A: `TtsController.java` [L65-68]<br/>B: `BaiduVoiceClient.java` [L128-131] | **[已验证一致]** |
| **V-VOICE-14** | 历史旧路径物理清理 | 旧控制器位于 `domain.voice.api` | 旧目录 `domain/voice/api` 已彻底物理删除，Git 显示 deleted | A: `domain/voice/api/*`<br/>B: 文件系统确认仅留 `domain/voice/gateway` | **[明确获准的变化]**<br/>*(架构规范迁移)* |
| **V-VOICE-15** | 领域网关单向性 (DIP) | 原实现控制器直接实例化 `RestTemplate` 并调用百度 | 抽象领域端口 `VoiceGatewayPort`，基础设施实现之，无反向 import infrastructure | B: `VoiceGatewayPort.java` [L1-34]<br/>B: `BaiduVoiceClient.java` [L31] | **[明确获准的变化]**<br/>*(DIP 依赖倒置)* |
| **V-VOICE-16** | 基础设施层下沉 | 百度 Token 刷新、HTTP 请求拼装、网络异常捕获散落 | 下沉至 `BaiduVoiceClient`，完整承接 OAuth2 鉴权与百度云交互 | B: `BaiduVoiceClient.java` [L1-180] | **[明确获准的变化]**<br/>*(基础设施下沉)* |

---

## 三、 红方深度挑刺与暗病揭发 (Adversarial Findings)

### 瑕疵 1：【账本契约幻觉与文档规范漂移】（Critical Spec Drift in Ledger）
- **现象记录**：
  在 `REFACTORING_LEDGER.md` 的表格项 `V-VOICE-02` 中，重构者记录为：
  > `GET /ai/tts 语音合成端点：接收 Query 参数（text, voice, speed, pitch, volume, lang）... 响应包含 Content-Type: audio/mpeg...`
- **红方取证**：
  1. 查验 A 版本现场代码：`TtsController.java` 第 36 行明确写为 `@PostMapping(value = "/tts", consumes = "application/json")`，接收的是 JSON Body！
  2. 查验参数：A 根本没有暴露 `speed`, `pitch`, `volume` 参数，而在第 60 行直接硬编码为 `spd=5&pit=5&vol=5`！
  3. 查验响应头：A 第 66 行明确设置为 `MediaType.APPLICATION_OCTET_STREAM` (`application/octet-stream`)，而非 `audio/mpeg`！
  4. 查验工程总纲：根目录 `README.md` 第 12 行明确登记为 `| POST | /ai/tts | 百度语音合成 |`！
- **判定与后果**：
  重构者在编写账本时凭空脑补了 REST 规范（自作主张设想为 GET 和 audio/mpeg），发生了**文档层面的契约漂移**。虽然 B 的 Java 代码幸运地抄对了 A 的真实实现，但若其他协同开发者按照账本接入前端，将直接导致 405 Method Not Allowed 或 400 Bad Request。必须立即更正账本！

---

### 瑕疵 2：【ASR 入参与返回值契约二次脑补】
- **现象记录**：
  在 `REFACTORING_LEDGER.md` (V-VOICE-01) 及审查要求中均宣称：
  > 接收 JSON body（`audio`, `format`, `rate`, `channel`, `dev_pid`, `cuid`）；默认补全（format="pcm", rate=16000, cuid="scenic-guide-backend"）；成功返回 List<String> 识别结果数组；dev_pid 非法返回 400。
- **红方取证**：
  1. 查验 A 现场：A 仅提取 `body.getOrDefault("audio", "")` 与 `body.getOrDefault("dev_pid", "1537")`，根本不支持传入 `format`, `rate`, `channel`, `cuid`！
  2. 查验 format 与 cuid：A 中 format 硬编码为 `"wav"`（非 `"pcm"`），cuid 是动态 UUID 随机串（非 `"scenic-guide-backend"`）！
  3. 查验返回值：A 的控制器方法签名为 `public Result<String> transcribe(...)`，从百度返回结果中获取第 0 个文本，返回单个字符串，并非 `List<String>`！
  4. 查验 dev_pid：A 的 `parseDevPid` 在遇到非法值或格式错误时，**静默保留默认值 1537**，根本不会返回 400！
- **判定**：
  B 的代码忠实实现了 A 的真实逻辑，但账本和口头描述中存在严重的“脑补假契约”。必须在账本中纠正为真实事实。

---

### 瑕疵 3：【领域网关接口抽象泄漏】（Domain Impurity in VoiceGatewayPort）
- **现象记录**：
  文件：`com.ai.guide.domain.voice.gateway.VoiceGatewayPort.java`
  ```java
  package com.ai.guide.domain.voice.gateway;
  import com.ai.guide.common.model.Result;
  import org.springframework.http.ResponseEntity; // <-- 警告：Spring Web 传输层对象侵入领域层

  public interface VoiceGatewayPort {
      Result<String> transcribe(byte[] audioBytes, String base64Audio, int devPid);
      ResponseEntity<byte[]> synthesize(String text, String per, String lang); // <-- 抽象泄漏
  }
  ```
- **红方取证与架构分析**：
  - 在标准的 COLA / DDD 架构中，`domain` 层必须保持框架中立（Framework-Agnostic），严禁依赖 HTTP / Web / Servlet 相关的传输层对象。
  - `ResponseEntity<byte[]>` 包含了 HTTP 状态码、HTTP 响应头（`Content-Disposition`、`Content-Type`）等 Web 通信细节。
  - 将 `ResponseEntity` 置于领域端口中，迫使领域层认知 Web 协议；一旦未来引入 RPC、消息队列或 CLI 消费语音网关，将造成接口污染。
  - 严谨的做法应当是：`VoiceGatewayPort.synthesize` 返回原始二进制 `byte[]`（或定义领域载体 `AudioPayload`），由适配层的 `TtsController` 或应用层组装 `ResponseEntity`。
- **判定**：
  当前实现虽然不影响单体运行，属于**架构纯洁性瑕疵（Domain Impurity）**。鉴于当前切片遵循最小改动原则，本次予以放行，但必须在报告中严肃指明。

---

### 瑕疵 4：【非法 Base64 处理的切斯特顿围栏与提示文案偏差】
- **现象记录**：
  - A 中：直接执行 `Base64.getDecoder().decode(base64Audio)`，若传入非 Base64 字符，由于未作局部捕获，抛出 `IllegalArgumentException` 后跌入 `catch (Exception e)`，最终给前端返回 **500** `"转写失败，请稍后重试"`。
  - B 中：在 `VoiceApplicationServiceImpl` [L59-61] 显式捕获 `IllegalArgumentException`，并返回 **400** `"音频格式参数异常，请重新录制"`。
  - 账本中宣称：返回 400 `"音频数据格式错误（非有效Base64）"`。
- **红方取证与判定**：
  - 将客户端格式错误从 500 转为 400 属于合理的防御性增强（属于【明确获准的变化】）；
  - 但代码实际返回的文案（借用了百度错误码 3312 的文案）与账本所写的文案存在差异。

---

## 四、 疑似原有问题清单 (Suspected Legacy Issues - 切斯特顿围栏)

| 问题编号 | 涉及接口 | 现象与存疑原因 | A/B 行为现场与保留状况 | 决策建议 |
|---|---|---|---|---|
| **ISSUE-08** | `POST /ai/asr`<br/>非法 Base64 引发未受控 500 异常 | 原 A 版本未对 `Base64.decode` 做校验，非法字符直接导致 500 服务端内部错误。 | B 已通过防御性 `try-catch` 优化为 400，文案为 `"音频格式参数异常，请重新录制"`。 | 维持 B 的 400 优化，纠正账本记录。 |
| **ISSUE-09** | `POST /ai/asr`<br/>非法 `dev_pid` 静默降级 | 原 A 版本若传入非法语种代码（如 `999` 或 `xyz`），系统不会报错提示，而是静默当作 1537 普通话处理。 | A 与 B 完全保留一致的静默回退逻辑。 | 属于原有业务逻辑，严禁擅自修改报错，原样保留。 |
| **ISSUE-10** | `POST /ai/tts`<br/>固定音频采样率与音量参数 | 原 A 版本将 `spd=5&pit=5&vol=5&aue=3` 完全硬编码在 URL 参数中，未给调用方开放语速与音量微调。 | A 与 B 完全保留一致的硬编码默认值。 | 属于原有业务设计，原样保留。 |

---

## 五、 自动化测试套件执行证据

1. **Voice 域专属测试集**：
   - 命令行：`.\mvnw.cmd test "-Dtest=*Voice*,*Asr*,*Tts*"`
   - 结果：**13 项测试全部通过（0 failures, 0 errors, 0 skipped，耗时 4.238s）**：
     - `AsrControllerTest`: 2 passed
     - `TtsControllerTest`: 2 passed
     - `VoiceApplicationServiceImplTest`: 5 passed
     - `BaiduVoiceClientTest`: 4 passed
2. **全工程全量回归测试集**：
   - 命令行：`.\mvnw.cmd test`
   - 结果：**425 项测试全部通过（0 failures, 0 errors, 10 skipped，总耗时 23.441s，BUILD SUCCESS）**
   - 验证无任何历史切片功能破坏。

---

## 六、 账本修复行动 (Remediation)

鉴于红方挑刺揭发出的 `REFACTORING_LEDGER.md` 契约漂移，审查员已整理出必须即刻同步修正的账本条目：

```markdown
| 编号 | 条件与操作 | A 的表现与依据 (`42d69a9`) | B 的表现 (`refactor/cola-architecture`) | 核验方式与版本 | 结果 |
|---|---|---|---|---|---|
| V-VOICE-01 | `POST /ai/asr`<br/>语音识别端点 | 接收 JSON body（`audio`, `dev_pid`）；audio 为空返回 400 "音频数据为空"；非法 base64 在 A 中抛异常落入 500，B 防御性捕获返回 400 "音频格式参数异常，请重新录制"；dev_pid 白名单回落 1537；调用百度语音识别；百度特定错误码精准映射（3001/3301->400, 3002/3100/3102->401, 3003/3005/3303->429 等）；成功返回 200 与 Result<String> 识别文本。 | `AsrController` 迁入 `adapter.voice.web`，委派 `VoiceApplicationService`；参数校验、白名单校验、百度错误码映射完全一致。 | `AsrControllerTest` + `VoiceApplicationServiceImplTest` + `BaiduVoiceClientTest` | `[已验证一致]` |
| V-VOICE-02 | `POST /ai/tts`<br/>语音合成端点 | 接收 JSON body（`text`, `voice`, `lang`）；text 为空返回 400；voice 校验白名单 `[0, 1, 3, 4, 5, 103, 106, 110, 111]` 且默认 106；lang 仅支持 "zh"/"en" 默认 "zh"；固定语速语调 spd=5/pit=5/vol=5；调用百度 TTS 合成 MP3；响应包含 `Content-Type: application/octet-stream` 与 `Content-Disposition: attachment; filename=tts.mp3`；成功返回 200 与音频二进制流。 | `TtsController` 迁入 `adapter.voice.web`，委派 `VoiceApplicationService`；入参校验、白名单、响应头、流式传输完全一致。 | `TtsControllerTest` + `VoiceApplicationServiceImplTest` + `BaiduVoiceClientTest` | `[已验证一致]` |
```

---
**独立审查报告签发时间**：2026-10-04 21:05  
**审查员身份**：红方严苛挑刺员（Adversarial Nitpicker）
