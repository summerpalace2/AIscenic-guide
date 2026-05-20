# 灵山智慧导游 API 文档

> 根路径: `https://你的域名` 或 `http://localhost:8080`

---

## 1. 对话接口

### 1.1 同步聊天

```
GET /ai/chat?message=xxx&sessionId=xxx
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:--:|--------|------|
| message | String | 否 | 你好 | 用户问题 |
| sessionId | String | 否 | default | 会话标识 |

**返回**: `text/plain` Markdown 文本（非 JSON）

```
### 1. 灵山大佛
- **价格**：包含在门票内
- **特色**：88米高青铜佛像...
```

### 1.2 流式聊天（SSE）

```
GET /ai/chat/stream?message=xxx&sessionId=xxx
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:--:|--------|------|
| message | String | 否 | 你好 | 用户问题 |
| sessionId | String | 否 | default | 会话标识 |

**返回**: `text/event-stream`

```
event:sentiment
data:positive

data:### 1. 灵山大佛
data:- **价格**：...
```

第一条 SSE 事件为情感标签（`event:sentiment`），后续为 Markdown 流式文本。

### 1.3 结构化聊天

```
GET /ai/chat/structured?message=xxx&sessionId=xxx
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:--:|--------|------|
| message | String | 是 | - | 用户问题 |
| sessionId | String | 否 | default | 会话标识 |

**返回**: `application/json`

```json
{
  "code": 200,
  "success": true,
  "message": "查询成功",
  "data": {
    "items": [
      { "name": "灵山大佛", "price": "包含在门票内", "feature": "88米高..." }
    ]
  }
}
```

---

## 2. 历史记录接口

### 2.1 会话列表

```
GET /ai/history/sessions
```

**返回**: `application/json`

```json
{
  "code": 200,
  "success": true,
  "message": "查询成功",
  "data": [
    {
      "sessionId": "sess_1778143901101_7dchgs",
      "title": "灵山有什么好玩的",
      "createTime": "2026-05-07 10:30:00",
      "lastUpdateTime": "2026-05-07 12:15:00",
      "messageCount": 6
    }
  ]
}
```

---

### 2.2 会话消息

```
GET /ai/history/messages?sessionId=xxx
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| sessionId | String | 是 | 会话标识 |

**返回**: `application/json`

```json
{
  "code": 200,
  "success": true,
  "message": "查询成功",
  "data": [
    { "role": "user", "content": "灵山有什么好玩的", "time": "..." },
    { "role": "assistant", "content": "### 1. 灵山大佛\n- **价格**：...", "time": "..." }
  ]
}
```

---

### 2.3 删除会话

```
DELETE /ai/history/sessions/{sessionId}
```

**返回**: `application/json`

```json
{ "code": 200, "success": true, "message": "删除成功", "data": null }
```

---

## 3. 语音识别接口

### 3.1 文件转写

```
POST /ai/asr
Content-Type: multipart/form-data
```

| 参数 | 类型 | 说明 |
|------|------|------|
| file | File | 音频文件（webm/mp3/wav） |

**返回**: `application/json`

```json
{ "code": 200, "success": true, "message": "识别成功", "data": "灵山有什么好玩的" }
```

### 3.2 流式转写（WebSocket）

```
ws://域名/ai/asr/stream
```

| 方向 | 类型 | 说明 |
|------|------|------|
| 前端→后端 | Binary | 音频帧（200ms 切片） |
| 后端→前端 | Text | 实时转写文字片段 |

**测试用例**:
```javascript
const ws = new WebSocket('wss://域名/ai/asr/stream');
ws.binaryType = 'arraybuffer';
ws.onmessage = e => console.log('识别:', e.data);
// 录音后: ws.send(audioChunk);
```

## 4. 偏好设置接口

### 4.1 保存偏好

```
POST /ai/preferences
Content-Type: application/json
```

**请求体**:
```json
{ "interest": "景点,美食", "duration": "全天", "crowd": "老人" }
```

**返回**:
```json
{ "code": 200, "success": true, "message": "偏好已保存", "data": null }
```

### 4.2 读取偏好

```
GET /ai/preferences
```

**返回**:
```json
{
  "code": 200,
  "success": true,
  "message": "查询成功",
  "data": { "interest": "景点,美食", "duration": "全天" }
}
```

## 5. 文件导入接口

```
POST /ai/import
Content-Type: multipart/form-data
```

| 参数 | 类型 | 说明 |
|------|------|------|
| file | File | Word(.docx) / Excel(.xlsx) / PDF |

**返回**: `application/json`

```json
{ "code": 200, "success": true, "message": "【xxx.docx】导入成功", "data": "xxx.docx" }
```



## 统一返回格式 `Result<T>`

```json
{
  "code": 200,      // 状态码: 200 成功 / 400 参数错误 / 500 服务器错误
  "success": true,  // 是否成功
  "message": "...", // 提示消息
  "data": { }       // 返回数据
}
```

> `/ai/chat` 和 `/ai/chat/stream` 不返回 Result，因为输出是纯 Markdown 文本和 SSE 流，前端直接消费。

## 
