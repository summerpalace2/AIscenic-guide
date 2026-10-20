# 本地启动与验证

Java 后端属于本仓库的 `ai/` Maven 模块。Node BFF 和 Vue 前端在单独的 Web 仓库；下面的 `<java-repo>`、`<web-repo>` 指各自在本机的实际检出路径，不是固定目录。需要 JDK 17+、Node.js 及相应依赖，供应商凭据由使用者提供，项目没有内置可用 Key。

## 准备配置

唯一共享模板是 [`.env.example`](.env.example)。首次运行复制为本目录 `.env`，填入必需值；已经存在 `.env` 时不要覆盖。`JWT_SECRET` 至少 32 个 UTF-8 字节；当前 Spring AI 初始化也要求非空 `DEEPSEEK_API_KEY`。BFF 使用独立 `YUYOUZHICE_AUTH_SECRET`，其启动器可协助初始化本地随机 secret。

模型、高德、Embedding、语音和基础设施按实际使用配置。模型假 key 只能用于隔离验证，进程启动成功不代表聊天可用。默认 SQLite 适合本地开发；Redis 聊天持久化、Qdrant RAG、PostgreSQL 与外部供应商分别需要可用环境。不要把真实 `.env` 或数据文件提交到 Git。

Java 按当前工作目录 `.env`、父目录 `.env` 的顺序读取，进程环境和 JVM 参数优先。BFF/工具用 `SCENIC_GUIDE_JAVA_DIR` 指定本 Java 模块目录，共享该文件但各自只加载需要的字段。

## 启动三个进程

终端一，进入 Java 模块目录：

```powershell
cd <java-repo>/ai
.\mvnw.cmd spring-boot:run
```

macOS/Linux 使用 `./mvnw spring-boot:run`。默认端口 8080；隔离验收请指定其他端口和临时数据库，不复用个人开发数据。

终端二，进入 Web 仓库：

```powershell
cd <web-repo>
$env:SCENIC_GUIDE_JAVA_DIR = '<java-repo>/ai'
node server/index.mjs
```

终端三，进入同一 Web 仓库的 Vue 工程：

```powershell
cd <web-repo>/frontend
npm run dev
```

Web 仓库首次运行需先按其 README 安装依赖；BFF 默认 3000，前端默认 5173，实际端口以各进程输出为准。供应商服务并不由这三个进程自动托管。

## 验证与测试

Java `/actuator/health`、BFF `/api/health` 可检查进程就绪；健康响应不证明高德、模型或其他供应商已正确授权。再依次验证聊天追问、规划预览和确认、正式保存/打开。供应商缺失、超时或未核验状态应在功能结果中如实呈现。

从 Java 仓库根目录运行后端离线回归：

```powershell
.\mvnw.cmd -pl ai test
```

测试配置使用临时 SQLite 和测试凭据，不需要复制个人 `.env`。真实基础设施测试需额外准备专门的隔离环境，参见 [CP6 基础设施说明](../tools/cp6-infra/README.md)。


## PDF 中文字体

应用内已携带 OFL 授权的 Noto Sans SC 静态中文字体，行程手册和运营报表默认嵌入实际使用的字形，不要求主机安装系统中文字体。默认保持 `PDF_FONT_FILE` 为空。若需替换，配置可读取的中文 TTF 绝对路径或带字面索引的 TTC 路径并重启；无效配置会导出失败，不静默回退为缺字字体。授权、来源、哈希与构建说明见 `src/main/resources/fonts/README.md`。自定义字库需自行确保授权与覆盖范围。
