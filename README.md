# 🏯 灵山智慧导游（AI Scenic Guide）

> 基于 **Spring Boot 3.4 + Spring AI + RAG** 的智能景区问答系统。
> 支持传统 RAG 与 Agentic RAG 双模式检索、多格式知识库导入、上下文压缩、三级缓存重排序、语音交互、偏好槽位追踪。

**在线体验**: [https://ggysummer.zeabur.app](https://www.ggysummer.shop/)

---

## 目录

- [项目结构](#项目结构)
- [技术栈](#技术栈)
- [外部 API](#外部-api)
- [核心功能](#核心功能)
- [技术实现](#技术实现)
- [接口文档](#接口文档)
- [部署](#部署)
- [配置](#配置)

---

## 项目结构

```
scenic-guide/
├── pom.xml                          # 父 POM
├── Dockerfile                       # 容器化部署（Zeabur 自动识别）
├── mvnw / mvnw.cmd                  # Maven Wrapper
│
├── ai/                              # AI 核心模块 — Spring Boot 3.4 + Java 17
│   ├── src/main/java/com/ai/guide/
│   │   ├── ScenicGuideApplication.java    # 启动类
│   │   ├── controller/                     # 7 个 REST 控制器
│   │   │   ├── ChatController.java         # 聊天（普通/流式/结构化 三接口）
│   │   │   ├── HistoryController.java      # 会话历史查询/删除
│   │   │   ├── ImportController.java       # 知识库文件导入 + 缓存管理
│   │   │   ├── PreferencesController.java  # 用户偏好 CRUD
│   │   │   ├── RagController.java          # 知识库统计/清空 + 管理接口
│   │   │   ├── AsrController.java          # 语音识别（百度 ASR）
│   │   │   └── TtsController.java          # 语音合成（百度 TTS）
│   │   │
│   │   ├── service/                        # 12 个业务服务
│   │   │   ├── ScenicDataImportService.java     # 知识库导入 + Qdrant 检索
│   │   │   ├── RerankService.java               # 百炼 Rerank + 三级缓存
│   │   │   ├── ParallelRagService.java          # Agentic RAG 并行检索
│   │   │   ├── QueryDecompositionService.java   # LLM 意图分析 + 子问题拆解
│   │   │   ├── ContextCompressionService.java   # 上下文摘要压缩
│   │   │   ├── SlotTrackingService.java         # 用户偏好槽位追踪
│   │   │   ├── IntentService.java               # 意图识别（三层剥离）
│   │   │   ├── ChatHistoryService.java          # 会话历史查询
│   │   │   ├── RedisChatMemory.java             # Redis 异步对话记忆
│   │   │   ├── QueryNormalizer.java             # 查询归一化（去噪/繁简）
│   │   │   ├── SimHash.java                     # SimHash 语义指纹
│   │   │   └── SentimentService.java            # 情感分析
│   │   │
│   │   ├── config/                         # 5 个配置类
│   │   │   ├── QdrantConfig.java               # Qdrant 客户端 + Payload 索引
│   │   │   ├── RedisConfig.java                # Redis 连接池配置
│   │   │   ├── AlibabaEmbeddingConfig.java     # DashScope Embedding
│   │   │   ├── RedisHealthCheck.java           # Redis 健康检查端点
│   │   │   └── WebMvcConfig.java              # MVC 配置
│   │   │
│   │   └── model/                          # 5 个实体类
│   │       ├── Result.java                    # 统一响应 {code, message, data, success}
│   │       ├── ConversationVO.java            # 会话列表项
│   │       ├── ChatHistoryVO.java             # 历史消息项
│   │       ├── ScenicResponse.java            # 结构化景点响应
│   │       └── ScenicItem.java                # 景点实体
│   │
│   ├── src/main/resources/
│   │   ├── application.yml               # 核心配置
│   │   └── Dockerfile
│   │
│   ├── pom.xml                            # 依赖管理
│   └── .env                               # 环境变量（本地开发）
│
└── README.md
```

---

## 技术栈

| 层             | 技术              | 版本                     | 说明                   |
| -------------- | ----------------- | ------------------------ | ---------------------- |
| **框架**       | Spring Boot       | 3.4.1                    | 基础框架               |
| **AI 框架**    | Spring AI         | 1.0.x                    | 统一 AI 抽象           |
| **语言**       | Java              | 17                       | LTS 版本               |
| **向量数据库** | Qdrant            | 自托管/Cloud             | 1536 维向量检索        |
| **缓存/记忆**  | Redis（Valkey）   | Aiven Cloud              | 会话记忆 + 槽位 + 摘要 |
| **大模型**     | DeepSeek          | deepseek-chat / v4-flash | 对话生成               |
| **向量化**     | DashScope         | text-embedding-v3        | 1536 维中文 Embedding  |
| **重排序**     | 阿里百炼          | gte-rerank-v2            | Rerank 精排            |
| **语音识别**   | 百度智能云        | 短语音识别-中文普通话    | ASR                    |
| **语音合成**   | 百度智能云        | 短文本在线合成           | TTS                    |
| **文档解析**   | Apache Tika + POI | —                        | Word/Excel/PDF 多格式  |
| **构建**       | Maven             | Wrapper                  | 依赖管理               |
| **部署**       | Docker + Zeabur   | —                        | 容器化云部署           |

---

## 外部 API

| API                 | 服务商       | 用途       | 接口地址                                                     |
| ------------------- | ------------ | ---------- | ------------------------------------------------------------ |
| DeepSeek Chat       | OpenAI 兼容  | 大模型对话 | `api.deepseek.com/v1/chat/completions`                       |
| DashScope Embedding | 阿里         | 文本向量化 | `dashscope.aliyuncs.com/api/v1/embeddings`                   |
| 百炼 Rerank         | 阿里         | 搜索重排序 | `dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank` |
| 百度 ASR            | 百度         | 语音转文字 | `vop.baidu.com/server_api`                                   |
| 百度 TTS            | 百度         | 文字转语音 | `tsn.baidu.com/text2audio`                                   |
| Qdrant              | 自托管/Cloud | 向量检索   | 配置中指定                                                   |
| Redis               | Aiven Cloud  | 会话/缓存  | 配置中指定                                                   |

---

## 核心功能

### 🎯 双模式 RAG

| 模式         | 适用          | 延迟  | 特点                                       |
| ------------ | ------------- | ----- | ------------------------------------------ |
| **正常模式** | 80% 常规问答  | 2-5s  | 规则判断闲聊 + Qdrant TOP30 + Rerank TOP10 |
| **深度思考** | 复杂/开放问题 | 5-10s | LLM 拆解子问题 + 并行检索 + 交叉验证重排   |

### 📚 多格式知识库

- 支持 **Excel / Word / PDF / Markdown / TXT** 及任意格式（Tika 兜底）
- NLP 二次切割 + 前缀继承（防止语义断裂）
- 自动去重（同一文件重复导入覆盖旧数据）

### 🧠 上下文管理

- **上下文压缩**: 旧消息自动摘要，保留最近 12 条原文
- **懒压缩**: 新增消息少于阈值时复用旧摘要
- **防漂移**: 摘要过长或压缩过频时自动重置

### ⚡ Rerank 三级缓存

- **L1** 精确查询（HashMap O(1)）
- **L2** 归一化查询（去噪/同义词）
- **L3** SimHash 语义查询（Hamming 距离 ≤ 3）
- 深度模式自动隔离（仅 L1，保证质量）

### 👤 偏好与槽位

- 兴趣（滑动窗口 3 条）、人群、时长、区域（单值覆盖）
- 自动从用户消息提取，注入 System Prompt 引导回答

### 🎙️ 语音交互

- 百度 ASR 语音输入
- 百度 TTS 语音输出（多种音色可选）

### 🔍 意图识别

- 三层剥离分析法：闲聊词 → 语气助词 → 赞美形容词
- 剥离后残余 < 2 字 → 自动判为闲聊，跳过 RAG

---

## 技术实现

### 1. RAG 检索链路

#### 正常模式（Traditional RAG）

```
用户提问 "灵山大佛多高？"
    │
    ├─ IntentService.classify() → QA（非闲聊）
    │
    ├─ ScenicDataImportService.queryKnowledge(query, 800)
    │   ├─ embeddingModel.embed(query)              // ~100ms
    │   ├─ Qdrant.searchAsync(limit=30)              // ~200ms
    │   └─ RerankService.rerank(query, docs, 10, 800)// ~200-500ms
    │       ├─ L1 精确缓存 hit → <1ms 返回
    │       ├─ L2 归一化缓存 hit → <1ms 返回
    │       ├─ L3 SimHash 缓存 hit → <1ms 返回
    │       └─ 未命中 → 调用百炼 Rerank API
    │
    ├─ buildUserPrompt(context, query)               // 问答模式 Prompt
    │
    └─ DeepSeek LLM 生成回复                          // ~2-3s

总耗时: 2.5-5s
```

#### 深度模式（Agentic RAG）

```
用户提问 "推荐含饮食住宿时间的完整路线"
    │
    ├─ QueryDecompositionService.decompose()            // ~500ms
    │   └─ LLM 拆解:
    │       {"needSearch":true, "subQueries":[
    │         "推荐旅游路线",
    │         "推荐当地特色美食",
    │         "推荐必去景点"
    │       ]}
    │
    ├─ ParallelRagService.search(subQueries)            // 并行 ~600ms
    │   ├─ Thread1 → Qdrant25 → Rerank15 (skip L2/L3)
    │   ├─ Thread2 → Qdrant25 → Rerank15 (skip L2/L3)
    │   └─ Thread3 → Qdrant25 → Rerank15 (skip L2/L3)
    │   │
    │   ├─ 交叉验证重排（出现频次 = 置信度）
    │   ├─ 过滤结构性碎片（去纯表头）
    │   └─ 取 TOP 12
    │
    ├─ queryKnowledge(baseQuery, 1500)                  // base 兜底
    │
    ├─ mergeContexts(parallel, base)                    // 合并去重 ≤15
    │
    └─ DeepSeek LLM 生成回复                             // ~3-5s

总耗时: 5-10s
```

### 2. 知识库导入切割流程

```
上传文件 → 格式识别 → 结构化解析 → 前缀继承 → NLP 切割 → 向量化 → Qdrant 入库
```

**多格式支持**:

| 格式     | 解析器             | 切割策略     | 特殊处理                    |
| -------- | ------------------ | ------------ | --------------------------- |
| Excel    | Apache POI         | 3 行一组     | Sheet 前缀 + 自动识别 ID 列 |
| Word     | Apache POI XWPF    | 段落+表格    | 表格行独立成段              |
| PDF      | PDFBox → Tika 降级 | L1+L2+L3     | 空则降级                    |
| Markdown | 正则               | 标题层级     | 代码块不切割                |
| TXT      | 段落合并           | 句子边界检测 | 软换行合并                  |
| 其他     | Apache Tika        | 通用解析     | 全格式兜底                  |

**前缀继承（防语义断裂）**:

```
原始: 【表格条目】项目: 建筑规模; 详细信息: 建筑面积7.2万㎡......（超长）

切割后:
  碎片1: 【表格条目】项目: 建筑规模...（首段，保留身份）
  碎片2: 【表格条目】(续): 外观以华藏塔风格...（补前缀，防断裂）
  碎片3: 【表格条目】(续): 顶部五座莲花圣塔...（同上）
```

### 3. 上下文压缩

| 参数                   | 值   | 说明                |
| ---------------------- | ---- | ------------------- |
| keepRecent             | 12   | 保留最近 N 条原文   |
| lazyCompressMin        | 12   | 新增 < N 不触发压缩 |
| maxSummaryLen          | 2000 | 摘要最大长度        |
| compressResetThreshold | 5    | 压缩 ≥ N 次后重置   |

```
消息 ≤12 条: 全部原文（不压缩）
消息 >12 条:
  ├─ 距上次新增 ≤12: 复用旧摘要 + 最近 12 条原文
  └─ 距上次新增 >12: 重新生成摘要（LLM 调用）+ 最近 12 条原文
```

### 4. 意图识别三层剥离

```
输入: "哇塞！灵山大佛真的太壮观了"
  │
  ├─ 第1层: 剥离闲聊词 "哇塞" → "真的太壮观了"
  ├─ 第2层: 剥离语气词/程度副词 "太""真的" → "壮观了"
  ├─ 第3层: 剥离赞美形容词 "壮观" → ""（< 2 字）
  │
  └─ 残余 < 2 字 → CHITCHAT → 跳过 RAG
```

### 5. Rerank 三级缓存

```
L1（精确）    Key: "灵山大佛多高|10"     → HashMap.get()    → <1ms
L2（归一化）  Key: "灵山大佛 多高|10"    → normalize()      → <1ms
L3（语义）    Key: SimHash(64bit)        → Hamming ≤ 3      → <1ms
缓存未命中    → 调用百炼 Rerank API（200-500ms）
```

深度模式子问题检索**跳过 L2/L3**，保证每次都是新鲜结果。

### 6. 性能优化总览

| 优化项        | 手段                              | 收益          |
| ------------- | --------------------------------- | ------------- |
| 异步写入      | addAsync 单线程守护               | 不阻塞响应    |
| 超时兜底      | 800ms/1500ms 超时返回 Qdrant 排序 | 稳定性        |
| 三级缓存      | L1/L2/L3 命中率 >70%              | 省 200-500ms  |
| 碎片截断      | Top10（正常）/ Top12（深度）      | 缩短 LLM 输入 |
| 双模式 Prompt | 闲聊/问答 Prompt 分离             | 避免机械回复  |
| 并行检索      | 4 线程并行子问题                  | 深度省 ~1.2s  |
| 懒压缩        | 新消息少时复用摘要                | 省 LLM 调用   |

---

## 接口文档

### 聊天

| 接口                                        | 方法 | 说明         |
| ------------------------------------------- | ---- | ------------ |
| `/ai/chat?message=&sessionId=&mode=`        | GET  | 普通对话     |
| `/ai/chat/stream?message=&sessionId=&mode=` | GET  | SSE 流式对话 |
| `/ai/chat/structured?message=&sessionId=`   | GET  | 结构化对话   |

> `mode`: `normal`（普通）/ `deep`（深度思考）

### 会话历史

| 接口                      | 方法   | 说明         |
| ------------------------- | ------ | ------------ |
| `/ai/history`             | GET    | 查询所有会话 |
| `/ai/history/{sessionId}` | GET    | 查询会话消息 |
| `/ai/history/{sessionId}` | DELETE | 删除会话     |

### 偏好设置

| 接口              | 方法 | 说明     |
| ----------------- | ---- | -------- |
| `/ai/preferences` | POST | 保存偏好 |
| `/ai/preferences` | GET  | 读取偏好 |

### 语音

| 接口      | 方法 | 说明                 |
| --------- | ---- | -------------------- |
| `/ai/asr` | POST | 语音识别（百度 ASR） |
| `/ai/tts` | POST | 语音合成（百度 TTS） |

### 知识库管理

| 接口                   | 方法   | 说明                   |
| ---------------------- | ------ | ---------------------- |
| `/ai/import`           | POST   | 上传文件（Excel/Word） |
| `/ai/rag/stats`        | GET    | 知识库统计             |
| `/ai/rag/document/all` | DELETE | 清空知识库             |
| `/ai/cache/stats`      | GET    | 缓存统计               |
| `/ai/cache/clear`      | POST   | 清空缓存               |

详细请求/响应示例见 **[API 接口文档](./API接口文档.md)**。

---

## 部署

### 环境变量

```
# 大模型
DEEPSEEK_API_KEY=sk-xxx

# 向量
DASHSCOPE_API_KEY=sk-xxx
QDRANT_HOST=xxx
QDRANT_PORT=6334
QDRANT_API_KEY=xxx

# Rerank
BAILIAN_RERANK_URL=https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rank

# 语音
BAIDU_API_KEY=xxx
BAIDU_SECRET_KEY=xxx

# Redis（Aiven Cloud）
REDIS_HOST=xxx
REDIS_PORT=11873
REDIS_PASSWORD=xxx
REDIS_DATABASE=0
REDIS_SSL=true
```

### 本地启动

```bash
cd ai
cp .env.example .env    # 填入密钥
./mvnw spring-boot:run
# 默认端口 8080
```

### Zeabur 部署

根目录 `Dockerfile` 自动构建 `ai/` 模块，在 Zeabur 面板配置环境变量即可。

---

## 配置

### application.yml

```yaml
spring:
  ai.openai:
    model: deepseek-chat          # 或 deepseek-v4-flash
    max-tokens: 2000
    temperature: 0.3
  data.redis:
    lettuce.pool.max-active: 8    # 连接池

context:
  keep-recent: 12                 # 上下文压缩保留条数
```

### 调优建议

| 参数                | 当前  | 更快            | 更准             |
| ------------------- | ----- | --------------- | ---------------- |
| Rerank 超时（正常） | 800ms | ↓ 500ms         | ↑ 1500ms         |
| Rerank topN（正常） | 10    | ↓ 5             | ↑ 15             |
| context.keep-recent | 12    | ↓ 6（省 token） | ↑ 20（保留更多） |
| max-tokens          | 2000  | ↓ 800           | ↑ 4000           |
| temperature         | 0.3   | —               | ↑ 0.7 更创意     |

---

## License

MIT License
