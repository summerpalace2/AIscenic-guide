# scenic-guide · 灵山智慧导游

## 项目结构

```
scenic-guide/
├── pom.xml              # 父 POM（多模块管理）
├── Dockerfile           # 容器化部署（Zeabur 直接识别）
├── mvnw / mvnw.cmd      # Maven Wrapper
├── .mvn/                # Wrapper 配置
├── .gitignore
│
├── ai/                  # AI 模块 — Spring AI (Java 17 + Maven)
│   ├── src/main/java/com/ai/guide/
│   │   ├── controller/  # Chat / History / Import / Preferences
│   │   ├── service/     # RAG / Redis / Rerank / Slot / ChatHistory
│   │   ├── config/      # Redis / Embedding / 健康检查
│   │   └── model/       # Result / VO
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── Dockerfile
│   ├── pom.xml
│   ├── .env / .env.example
│   └── mvnw / mvnw.cmd / .mvn
│
├── backend/             # 后端 — 未来 FastAPI (Python)
├── frontend/            # 前端 — 未来 Vue3 + TypeScript
├── docs/                # 项目文档
└── scripts/             # 部署运维脚本
```

## 技术栈 (ai/)

| 层 | 技术 |
|---|---|
| 大模型 | DeepSeek (deepseek-chat) |
| 向量化 | 阿里 DashScope text-embedding-v2 (1536维) |
| 向量库 | Zilliz Cloud (Milvus) |
| 重排序 | 阿里百炼 gte-rerank-v2 |
| 对话记忆 | Redis (Aiven Cloud / Valkey) |
| 框架 | Spring Boot 3.4 + Spring AI 1.0.0-M3 |

## 快速开始

```bash
cd ai
cp .env.example .env    # 填入密钥
./mvnw spring-boot:run
```

## 服务器部署 (Zeabur)

根目录 `Dockerfile` 自动构建 `ai/` 模块。在 Zeabur 面板配置好环境变量即可：

```
DEEPSEEK_API_KEY / DASHSCOPE_API_KEY / BAILIAN_API_KEY
ZILLIZ_TOKEN / ZILLIZ_ENDPOINT
REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_DATABASE / REDIS_SSL
```
