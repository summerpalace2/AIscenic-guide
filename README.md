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

## Docker 开发启动

```bash
# 1. 构建镜像（含编译）
docker build -t scenic-guide .

# 2. 创建 .env 并填入 API Key
cp .env.example .env
# 编辑 .env，填入以下密钥（从 .env.example 复制或手动填写）：
# DEEPSEEK_API_KEY=YOUR_DEEPSEEK_API_KEY
# DASHSCOPE_API_KEY=YOUR_DASHSCOPE_API_KEY
# BAILIAN_API_KEY=YOUR_BAILIAN_API_KEY
# ZILLIZ_TOKEN=YOUR_ZILLIZ_TOKEN
# ZILLIZ_ENDPOINT=YOUR_ZILLIZ_ENDPOINT
# REDIS_HOST=YOUR_REDIS_HOST
# REDIS_PORT=6379
# REDIS_PASSWORD=YOUR_REDIS_PASSWORD
# REDIS_DATABASE=0
# REDIS_SSL=true

# 3. 运行容器
docker run -it --rm -p 8081:8081 -v "$(pwd):/workspace" scenic-guide

# 4. 容器内启动服务
java -jar /app/app.jar --server.port=8081

# 5. 测试
curl -G 'http://localhost:8081/ai/chat' --data-urlencode 'message=你好' --data-urlencode 'sessionId=test'
```


## Docker 开发启动（Windows PowerShell）

```powershell
# 1. 构建镜像（含编译）
docker build -t scenic-guide .

# 2. 创建 .env 并填入 API Key
copy .env.example .env
# 编辑 .env，填入以下密钥：
# DEEPSEEK_API_KEY=YOUR_DEEPSEEK_API_KEY
# DASHSCOPE_API_KEY=YOUR_DASHSCOPE_API_KEY
# BAILIAN_API_KEY=YOUR_BAILIAN_API_KEY
# ZILLIZ_TOKEN=YOUR_ZILLIZ_TOKEN
# ZILLIZ_ENDPOINT=YOUR_ZILLIZ_ENDPOINT
# REDIS_HOST=YOUR_REDIS_HOST
# REDIS_PORT=6379
# REDIS_PASSWORD=YOUR_REDIS_PASSWORD
# REDIS_DATABASE=0
# REDIS_SSL=true

# 3. 运行容器（Windows）
docker run -it --rm -p 8081:8081 -v "${PWD}:/workspace" scenic-guide

# 4. 容器内启动服务
java -jar /app/app.jar --server.port=8081

# 5. 测试
curl -G 'http://localhost:8081/ai/chat' --data-urlencode 'message=你好' --data-urlencode 'sessionId=test'
```

