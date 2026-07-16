# AI数字人智能景区导览系统 — 后端RESTful API文档

> 本文档定义后端对外提供的 RESTful API 接口规范，供前端（游客端 + 管理端）调用。
>
> - **Base URL**: `http://<host>:8000/api/v1`
> - **数据格式**: 所有请求/响应均为 `application/json`
> - **认证方式**: Bearer Token（将JWT放入请求头 `Authorization: Bearer <token>`）
> - **HTTP 状态码语义**：
>   - `200` 请求成功，data 中包含业务数据
>   - `201` 资源创建成功
>   - `400` 请求参数校验失败，属于客户端错误
>   - `401` 未认证（Token 缺失/无效/过期）
>   - `403` 已认证但无权限（角色不符、频率限制）
>   - `404` 请求的资源不存在
>   - `500` 服务端内部错误
> - **统一响应体**: `{ "code": int, "success": bool, "message": string, "data": any }`
>   - `code` 字段用于业务错误细分（如区分"手机号格式错误"与"手机号已注册"），前端可据此做精细化提示
>   - `success` 字段标识业务是否成功（`true` 为成功，`false` 为业务错误），前端可据此快速判断

---

## HTTP 状态码约定

| HTTP Status | code 范围 | 语义 | 典型场景 |
|------------|----------|------|---------|
| **200** | `0` | 成功 | 查询/更新/删除成功 |
| **201** | `0` | 创建成功 | POST 注册、POST 上传文档 |
| **400** | `40001-40999` | 请求错误 | 参数校验失败、格式错误、业务规则冲突 |
| **401** | `40101-40199` | 未认证 | Token 缺失、无效、已过期 |
| **403** | `40301-40399` | 无权限 | 角色不足、频率限制、敏感词拦截 |
| **404** | `40401-40499` | 资源不存在 | 用户/文档/会话等资源未找到 |
| **500** | `50001-50999` | 服务端错误 | 内部异常、AI端超时、数据库连接失败 |

---

## 目录

1. [认证接口](#1-认证接口)
2. [用户接口](#2-用户接口)
3. [知识库接口](#3-知识库接口)
4. [对话接口](#4-对话接口)
5. [数字人配置接口](#5-数字人配置接口)
6. [数据分析接口](#6-数据分析接口)
7. [位置服务接口](#7-位置服务接口)
8. [管理后台接口](#8-管理后台接口)
9. [WebSocket接口](#9-websocket接口)
10. [附录：错误码说明](#10-附录错误码说明)

---

## 1. 认证接口

### 1.1 游客注册

注册新游客账号。

```
POST /auth/register
```

**Request Body:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | string | 是 | 手机号，11位 |
| password | string | 是 | 登录密码 |
| nickname | string | 否 | 昵称，不传则默认"游客XXXX" |

**Response `data`:**

```json
{
    "user_id": "uuid_string",
    "token": "jwt_token_string",
    "expires_in": 3600
}
```

---

### 1.2 游客登录

```
POST /auth/login
```

**Request Body:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | string | 是 | 手机号 |
| password | string | 是 | 密码 |

**Response `data`:**

```json
{
    "user_id": "uuid_string",
    "token": "jwt_token_string",
    "expires_in": 3600
}
```

---

### 1.3 微信登录

```
POST /auth/wechat
```

**Request Body:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | string | 是 | 微信OAuth临时凭证 |

**Response `data`:** 同游客登录。

---

### 1.4 管理员登录

```
POST /auth/admin/login
```

**Request Body:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | string | 是 | 管理员账号 |
| password | string | 是 | 密码 |

**Response `data`:**

```json
{
    "user_id": "uuid_string",
    "token": "jwt_token_string",
    "refresh_token": "refresh_token_string",
    "expires_in": 3600,
    "role": "admin"
}
```

---

### 1.5 刷新Token

```
POST /auth/refresh
```

**Request Body:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| refresh_token | string | 是 | 登录时返回的refresh_token |

**Response `data`:** 新的 token + refresh_token。

---

### 1.6 获取当前用户

```
GET /auth/me
```

**Headers:** `Authorization: Bearer <token>`

**Response `data`:**

```json
{
    "user_id": "uuid_string",
    "nickname": "张三",
    "avatar": "http://...",
    "role": "tourist",
    "phone": "138xxxxxxxx",
    "interests": ["history", "nature"],
    "created_at": "2026-05-07T10:00:00Z"
}
```

---

## 2. 用户接口

### 2.1 获取用户信息

```
GET /users/{user_id}
```

**Response `data`:** 同 `/auth/me`。

---

### 2.2 更新兴趣标签

```
PUT /users/{user_id}/interests
```

**Request Body:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| interests | string[] | 是 | 兴趣标签数组，可选值：history / nature / food / family |

**Response `data`:** 更新后的完整用户信息。

---

### 2.3 更新用户资料

```
PUT /users/{user_id}/profile
```

**Request Body:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| nickname | string | 否 | 昵称 |
| avatar | string | 否 | 头像URL |

---

## 3. 知识库接口

> 以下接口均需 **管理员权限**（`admin` / `super_admin`）。

### 3.1 文档列表

```
GET /knowledge
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| category | string | 否 | 分类筛选：history / culture / faq / notice |
| keyword | string | 否 | 标题/内容关键词搜索 |
| page | int | 否 | 页码，默认1 |
| size | int | 否 | 每页条数，默认20，最大100 |

**Response `data`:**

```json
{
    "total": 120,
    "page": 1,
    "size": 20,
    "items": [
        {
            "id": "uuid_string",
            "title": "故宫历史沿革",
            "category": "history",
            "content_snippet": "故宫始建于公元1406年...",
            "status": "published",
            "chunk_count": 0,
            "created_at": "2026-05-01T10:00:00Z",
            "updated_at": "2026-05-07T10:00:00Z"
        }
    ]
}
```

---

### 3.2 文档详情

```
GET /knowledge/{doc_id}
```

**Response `data`:**

```json
{
    "id": "uuid_string",
    "title": "故宫历史沿革",
    "category": "history",
    "content": "完整内容...",
    "file_url": "http://minio/.../original.pdf",
    "status": "published",
    "vector_status": "synced",
    "chunk_count": 15,
    "tags": ["故宫", "明朝", "历史"],
    "created_at": "2026-05-01T10:00:00Z",
    "updated_at": "2026-05-07T10:00:00Z"
}
```

---

### 3.3 创建/上传文档

```
POST /knowledge
```

**Content-Type:** `multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | string | 是 | 文档标题 |
| category | string | 是 | 分类：history / culture / faq / notice |
| content | string | 否 | 直接输入的文本内容（与file二选一） |
| file | file | 否 | 上传文件（PDF/DOCX/TXT，与content二选一） |
| tags | string[] | 否 | 标签数组 |

**Response `data`:** 创建的完整文档对象，`vector_status` 初始为 `pending`。

---

### 3.4 编辑文档

```
PUT /knowledge/{doc_id}
```

**Content-Type:** `application/json`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | string | 否 | 新标题 |
| category | string | 否 | 新分类 |
| content | string | 否 | 新内容 |
| tags | string[] | 否 | 新标签 |

更新后自动将 `vector_status` 置为 `pending`，触发重新索引。

---

### 3.5 删除文档

```
DELETE /knowledge/{doc_id}
```

**Response:** `data: null`

删除后同步触发向量库中对应chunk的清除。

---

### 3.6 获取分类列表

```
GET /knowledge/categories
```

**Response `data`:**

```json
[
    {"key": "history", "label": "历史文化", "count": 45},
    {"key": "culture", "label": "人文艺术", "count": 30},
    {"key": "faq", "label": "常见问题", "count": 25},
    {"key": "notice", "label": "游览须知", "count": 20}
]
```

---

### 3.7 触发向量同步

```
POST /knowledge/{doc_id}/sync
```

手动触发指定文档的向量化索引重建。

**Response `data`:**

```json
{
    "doc_id": "uuid_string",
    "vector_status": "syncing",
    "chunk_count": 15
}
```

---

## 4. 对话接口

### 4.1 发送文本消息

```
POST /dialog/message
```

**Request Body:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| content | string | 是 | 用户文本消息 |
| session_id | string | 否 | 会话ID，不传则创建新会话 |
| scenic_spot_id | string | 否 | 关联的景点ID（实时讲解场景） |

**Response `data`:**

```json
{
    "message_id": "uuid_string",
    "session_id": "sess_abc123",
    "reply": "故宫始建于公元1406年...",
    "emotion": "enthusiastic",
    "intent": "qa_history",
    "audio_url": "http://.../tts_output.mp3",
    "duration_ms": 1200
}
```

---

### 4.2 发送语音消息

```
POST /dialog/voice
```

**Content-Type:** `multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| audio | file | 是 | 录音文件（WAV/MP3/M4A） |
| session_id | string | 否 | 会话ID |

**处理流程:** 上传 → 后端转发给AI端ASR → 转写文本 → 进入问答流程。

> 当前为 Mock 实现，暂用固定文本模拟 ASR 识别结果。

**Response `data`:** 同文本消息。

---

### 4.3 会话列表

```
GET /dialog/sessions
```

**Headers:** `Authorization: Bearer <token>`

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码 |
| size | int | 否 | 每页条数 |

**Response `data`:**

```json
{
    "total": 10,
    "items": [
        {
            "session_id": "sess_abc123",
            "title": "故宫历史相关咨询",
            "message_count": 8,
            "last_message": "那故宫有多少个房间呢？",
            "last_time": "2026-05-07T14:30:00Z"
        }
    ]
}
```

---

### 4.4 会话消息历史

```
GET /dialog/sessions/{session_id}/messages
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| before_id | string | 否 | 分页：取该消息之前的消息 |
| limit | int | 否 | 默认50 |

**Response `data`:**

```json
{
    "session_id": "sess_abc123",
    "total": 8,
    "has_more": false,
    "items": [
        {
            "message_id": "msg_001",
            "role": "user",
            "content": "故宫建于哪一年？",
            "audio_url": null,
            "emotion": "curious",
            "created_at": "2026-05-07T14:20:00Z"
        },
        {
            "message_id": "msg_002",
            "role": "assistant",
            "content": "故宫始建于公元1406年...",
            "audio_url": "http://.../tts_001.mp3",
            "emotion": "enthusiastic",
            "created_at": "2026-05-07T14:20:05Z"
        }
    ]
}
```

---

### 4.5 删除会话

```
DELETE /dialog/sessions/{session_id}
```

---

## 5. 数字人配置接口

> 需 **管理员权限**。

### 5.1 获取当前配置

```
GET /admin/digital-human
```

**Response `data`:**

```json
{
    "appearance": {
        "model_id": "model_female_01",
        "outfit": "traditional",
        "hairstyle": "long"
    },
    "voice": {
        "voice_id": "voice_warm_01",
        "speed": 1.0,
        "pitch": 1.0,
        "volume": 1.0
    },
    "emotion_style": "friendly",
    "preview_url": "http://.../preview.mp4"
}
```

---

### 5.2 更新配置

```
PUT /admin/digital-human
```

**Request Body:**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| appearance | object | 否 | 外观参数（model_id, outfit, hairstyle） |
| voice | object | 否 | 声音参数（voice_id, speed, pitch） |
| emotion_style | string | 否 | 表情风格：friendly / professional / lively |

**Response `data`:** 更新后的完整配置。

---

### 5.3 可用音色列表

```
GET /admin/digital-human/voices
```

**Response `data`:**

```json
[
    {"voice_id": "voice_warm_01", "name": "温柔女声", "gender": "female", "preview_url": "..."},
    {"voice_id": "voice_lively_01", "name": "活泼少女", "gender": "female", "preview_url": "..."},
    {"voice_id": "voice_professional_01", "name": "专业男声", "gender": "male", "preview_url": "..."}
]
```

---

### 5.4 可用外观列表

```
GET /admin/digital-human/appearances
```

**Response `data`:** 外观模板列表（model_id, 预览图URL, 服装, 发型等）。

---

## 6. 数据分析接口

> 需 **管理员权限**。

### 6.1 数据大屏概览

```
GET /analytics/dashboard
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| period | string | 否 | 时间范围：today / week / month，默认today |

**Response `data`:**

```json
{
    "service_count": {
        "total": 1250,
        "today": 380,
        "week": 1250,
        "trend": "+12%"
    },
    "active_users": {
        "current": 23,
        "peak_today": 45
    },
    "satisfaction_trend": [
        {"date": "05-01", "score": 4.5},
        {"date": "05-02", "score": 4.7}
    ],
    "hot_questions_top10": [
        {"rank": 1, "question": "故宫几点开门？", "count": 230},
        {"rank": 2, "question": "门票多少钱？", "count": 185}
    ],
    "emotion_distribution": {
        "positive": 0.72,
        "neutral": 0.20,
        "negative": 0.08
    }
}
```

---

### 6.2 热门问答排行

```
GET /analytics/hot-questions
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| period | string | 否 | today / week / month |
| top_n | int | 否 | 返回条数，默认10 |

---

### 6.3 情感趋势曲线

```
GET /analytics/sentiment-trend
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| start_date | string | 否 | YYYY-MM-DD |
| end_date | string | 否 | YYYY-MM-DD |
| granularity | string | 否 | day / hour，默认day |

**Response `data`:**

```json
{
    "data": [
        {"time": "2026-05-07", "positive": 0.75, "neutral": 0.18, "negative": 0.07}
    ]
}
```

---

### 6.4 服务人次统计

```
GET /analytics/service-count
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| start_date | string | 否 | 起始日期 |
| end_date | string | 否 | 结束日期 |
| granularity | string | 否 | day / hour |

---

### 6.5 报告列表

```
GET /analytics/reports
```

**Query Parameters:** page / size / type

**Response `data`:**

```json
{
    "total": 5,
    "items": [
        {
            "id": "uuid",
            "title": "5月7日游客感受度报告",
            "type": "daily",
            "period_start": "2026-05-07",
            "period_end": "2026-05-07",
            "status": "completed",
            "created_at": "2026-05-08T02:00:00Z"
        }
    ]
}
```

---

### 6.6 报告详情

```
GET /analytics/reports/{report_id}
```

**Response `data`:** 报告完整JSON，包含所有图表数据。

---

### 6.7 导出报告PDF

```
GET /analytics/reports/{report_id}/export
```

**Response:** 直接返回PDF文件流（`Content-Type: application/pdf`）。

---

## 7. 位置服务接口

### 7.1 景点列表

```
GET /location/scenic-spots
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| category | string | 否 | 分类筛选：history / nature / facility |
| keyword | string | 否 | 名称关键词搜索 |

**Response `data`:**

```json
{
    "total": 30,
    "items": [
        {
            "id": "uuid",
            "name": "太和殿",
            "category": "history",
            "description": "故宫最大的宫殿...",
            "longitude": 116.397,
            "latitude": 39.916,
            "audio_url": "http://.../scenic_001.mp3",
            "tags": ["必看", "明代建筑"]
        }
    ]
}
```

---

### 7.2 景点详情

```
GET /location/scenic-spots/{spot_id}
```

---

### 7.3 附近景点

```
GET /location/nearby
```

**Query Parameters:**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| longitude | float | 是 | 当前GPS经度 |
| latitude | float | 是 | 当前GPS纬度 |
| radius | int | 否 | 搜索半径（米），默认500 |

**Response `data`:** 景点列表，增加 `distance` 字段（距当前点的米数）。

---

## 8. 管理后台接口

### 8.1 管理员列表

```
GET /admin/users
```

需 `super_admin` 权限。

**Response `data`:**

```json
{
    "items": [
        {"id": "uuid", "username": "admin01", "role": "admin", "last_login": "..."}
    ]
}
```

---

### 8.2 创建管理员

```
POST /admin/users
```

需 `super_admin` 权限。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | string | 是 | 管理员账号 |
| password | string | 是 | 密码 |
| role | string | 否 | admin / super_admin，默认admin |

---

### 8.3 修改角色

```
PUT /admin/users/{user_id}/role
```

需 `super_admin` 权限。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| role | string | 是 | 新角色 |

---

### 8.4 操作日志

```
GET /admin/logs
```

**Query Parameters:** page / size / start_date / end_date / action_type

**Response `data`:**

```json
{
    "items": [
        {
            "admin_id": "uuid",
            "action": "knowledge.create",
            "target": "doc_xxx",
            "detail": "上传文档《故宫历史》",
            "ip": "192.168.1.1",
            "created_at": "2026-05-07T10:00:00Z"
        }
    ]
}
```

---

### 8.5 获取系统设置

```
GET /admin/settings
```

**Response `data`:**

```json
{
    "scenic_name": "故宫博物院",
    "business_hours": "08:30-17:00",
    "welcome_message": "欢迎来到故宫！",
    "maintenance_mode": false,
    "language": "zh-CN"
}
```

---

### 8.6 更新系统设置

```
PUT /admin/settings
```

---

## 9. WebSocket接口

### 9.1 建立连接

```
ws://<host>:8000/ws/{user_id}
```

**Headers:** 建立连接时需携带 `Authorization: Bearer <token>`。

---

### 9.2 消息协议

#### 客户端 → 服务端

| type | 字段 | 说明 |
|------|------|------|
| `chat` | `{ content: string, session_id?: string }` | 发送聊天消息 |
| `ping` | `{}` | 心跳保活 |

#### 服务端 → 客户端

| type | 字段 | 说明 |
|------|------|------|
| `chunk` | `{ content: string, index: int }` | AI回答流式片段 |
| `emotion` | `{ label: string }` | 数字人情感变化通知 |
| `done` | `{ session_id: string }` | 回答结束 |
| `error` | `{ message: string, code: int }` | 错误信息 |
| `pong` | `{}` | 心跳回复 |

---

### 9.3 对话流程时序

```
前端                         后端                      AI端
 │                          │                        │
 │── ws.connect(user_id) ──→│                        │
 │                          │── /ai/chat/stream ────→│
 │                          │    (userId, message)   │
 │                          │←── SSE chunk ──────────│
 │←── ws.chunk(content) ────│                        │
 │                          │←── SSE chunk ──────────│
 │←── ws.chunk(content) ────│                        │
 │                          │←── SSE [DONE] ─────────│
 │←── ws.emotion(label) ────│                        │
 │←── ws.done(session_id) ──│                        │
 │                          │                        │
```

---

## 10. 附录：错误码说明

> 每个错误码绑定到对应的 HTTP 状态码。前端优先判断 `http status` 做分类处理，再根据 `code` 字段做具体提示文案。

### 成功码

| HTTP | code | message | 说明 |
|------|------|---------|------|
| 200 | 0 | success | 请求成功 |
| 201 | 0 | created | 资源创建成功 |

### 客户端错误

| HTTP | code | message | 说明 |
|------|------|---------|------|
| 400 | 40001 | 参数校验失败 | 请求体字段格式或类型不符合预期 |
| 400 | 40002 | 手机号格式不正确 | `phone` 字段不符合 11 位手机号 |
| 400 | 40003 | 资源已存在 | 手机号/用户名重复注册 |
| 400 | 40004 | 不支持的文件类型 | 上传文件类型不在允许列表内 |
| 400 | 40005 | 文件大小超限 | 单文件超过 50MB 上限 |
| 401 | 40101 | 未认证 | 缺少 Authorization 请求头 |
| 401 | 40102 | Token 无效 | JWT 签名验证失败或格式错误 |
| 401 | 40103 | Token 已过期 | Token 超过有效期，需刷新 |
| 403 | 40301 | 无权限 | 当前角色无权执行该操作 |
| 403 | 40302 | 频率限制 | 请求频率超过限制（如登录 5次/分钟） |
| 403 | 40303 | 敏感词拦截 | 输入内容命中敏感词库 |
| 404 | 40401 | 用户不存在 | 用户 ID 未找到 |
| 404 | 40402 | 文档不存在 | 知识库文档 ID 未找到 |
| 404 | 40403 | 会话不存在 | 对话会话 ID 未找到 |
| 404 | 40404 | 景点不存在 | 景点 ID 未找到 |
| 404 | 40405 | 报告不存在 | 分析报告 ID 未找到 |

### 服务端错误

| HTTP | code | message | 说明 |
|------|------|---------|------|
| 500 | 50001 | 服务内部错误 | 未预期的运行时异常 |
| 500 | 50002 | AI服务不可用 | AI端请求超时或连接失败 |
| 500 | 50003 | 数据库连接失败 | PostgreSQL连接池耗尽或宕机 |
| 500 | 50004 | 文件存储异常 | MinIO 上传/下载失败 |

---

> **文档版本**: v1.0 | **最后更新**: 2026-05-07 | **维护人**: 后端负责人
