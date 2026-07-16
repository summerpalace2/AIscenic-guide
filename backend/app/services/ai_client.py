"""
AI 端 HTTP 客户端，封装对 AI 服务（Java Spring Boot，端口 8081/8080）的调用
接口定义见 docs/API文档.md

注意：dialog_service.py 目前使用 mock 回复，未实际调用 AI 端
联调时需确保 AI_SERVICE_URL 指向正确的 AI 服务地址
"""
import httpx
import base64
from pathlib import Path
from typing import AsyncGenerator, Optional
from app.core.config import get_settings

settings = get_settings()

# 文件后缀 → Content-Type 映射，按 AI 端 /ai/import 支持的格式
MIME_MAP = {
    '.pdf': 'application/pdf',
    '.doc': 'application/msword',
    '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    '.xls': 'application/vnd.ms-excel',
    '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    '.txt': 'text/plain',
    '.md': 'text/markdown',
    '.csv': 'text/csv',
}

# 支持向量化的文档格式白名单
DOC_EXTENSIONS = {'.pdf', '.doc', '.docx', '.xls', '.xlsx'}

class AiServiceClient:
    def __init__(self, base_url=None):
        self.base_url = base_url or settings.AI_SERVICE_URL
        self.client = httpx.AsyncClient(timeout=60.0)

    async def close(self):
        await self.client.aclose()

    # ───── 对话接口 ─────

    async def chat_structured(self, message: str, session_id: str = "default", mode: str = "normal") -> dict:
        """GET /ai/chat/structured → 返回 JSON 结构数据"""
        try:
            resp = await self.client.get(
                f'{self.base_url}/ai/chat/structured',
                params={'message': message, 'sessionId': session_id, 'mode': mode}
            )
            return resp.json()
        except Exception as e:
            print(f'[AiClient] chat_structured 失败: {e}')
            return {'code': 500, 'success': False, 'message': str(e), 'data': None}

    async def chat_stream(self, message: str, session_id: str = "default", mode: str = "normal") -> AsyncGenerator[str, None]:
        """GET /ai/chat/stream → SSE 流式响应（含 sentiment 事件）"""
        try:
            async with self.client.stream(
                'GET', f'{self.base_url}/ai/chat/stream',
                params={'message': message, 'sessionId': session_id, 'mode': mode}
            ) as resp:
                async for line in resp.aiter_lines():
                    if line.startswith('data:'):
                        yield line[5:].strip()
                    elif line.startswith('event:'):
                        # 透传 sentiment 等事件名
                        yield f'__event__:{line[6:].strip()}'
        except Exception as e:
            print(f'[AiClient] chat_stream 失败: {e}')
            yield f'AI 服务暂时不可用: {e}'

    async def chat_sync(self, message: str, session_id: str = "default", mode: str = "normal") -> str:
        """GET /ai/chat → 返回纯文本 Markdown"""
        try:
            resp = await self.client.get(
                f'{self.base_url}/ai/chat',
                params={'message': message, 'sessionId': session_id, 'mode': mode}
            )
            return resp.text
        except Exception as e:
            print(f'[AiClient] chat_sync 失败: {e}')
            return ''

    # ───── 历史记录接口 ─────

    async def get_history_sessions(self) -> list:
        """GET /ai/history/sessions → 会话列表"""
        try:
            resp = await self.client.get(f'{self.base_url}/ai/history/sessions')
            json = resp.json()
            return json.get('data', []) if json.get('success') else []
        except Exception as e:
            print(f'[AiClient] get_history_sessions 失败: {e}')
            return []

    async def get_history_messages(self, session_id: str) -> list:
        """GET /ai/history/messages?sessionId=xxx → 消息列表"""
        try:
            resp = await self.client.get(
                f'{self.base_url}/ai/history/messages',
                params={'sessionId': session_id}
            )
            json = resp.json()
            return json.get('data', []) if json.get('success') else []
        except Exception as e:
            print(f'[AiClient] get_history_messages 失败: {e}')
            return []

    async def delete_session(self, session_id: str) -> bool:
        """DELETE /ai/history/sessions/{sessionId}"""
        try:
            resp = await self.client.delete(f'{self.base_url}/ai/history/sessions/{session_id}')
            json = resp.json()
            return json.get('success', False)
        except Exception as e:
            print(f'[AiClient] delete_session 失败: {e}')
            return False

    # ───── 语音识别 ─────

    async def asr_transcribe(self, file_path: str) -> str:
        """POST /ai/asr → 语音转文字（WAV → base64 → JSON body）
        Java 端接收 JSON: {"audio": "data:audio/wav;base64,..."}
        """
        try:
            with open(file_path, 'rb') as f:
                b64 = base64.b64encode(f.read()).decode()
            audio_data_url = f'data:audio/wav;base64,{b64}'
            resp = await self.client.post(
                f'{self.base_url}/ai/asr',
                json={'audio': audio_data_url}
            )
            json = resp.json()
            return json.get('data', '') if json.get('success') else ''
        except Exception as e:
            print(f'[AiClient] asr_transcribe 失败: {e}')
            return ''

    async def tts_synthesize(self, text: str, voice: str = "106") -> Optional[bytes]:
        """POST /ai/tts → 语音合成（返回 MP3 字节）
        voice: 106=度博文(男), 0=度小美(女), 4=度丫丫(女)
        """
        try:
            resp = await self.client.post(
                f'{self.base_url}/ai/tts',
                json={'text': text, 'voice': voice}
            )
            if resp.status_code == 200:
                return resp.content
            return None
        except Exception as e:
            print(f'[AiClient] tts_synthesize 失败: {e}')
            return None

    # ───── 偏好设置 ─────

    async def save_preferences(self, data: dict) -> bool:
        """POST /ai/preferences"""
        try:
            resp = await self.client.post(f'{self.base_url}/ai/preferences', json=data)
            return resp.json().get('success', False)
        except Exception as e:
            print(f'[AiClient] save_preferences 失败: {e}')
            return False

    async def get_preferences(self) -> dict:
        """GET /ai/preferences"""
        try:
            resp = await self.client.get(f'{self.base_url}/ai/preferences')
            json = resp.json()
            return json.get('data', {}) if json.get('success') else {}
        except Exception as e:
            print(f'[AiClient] get_preferences 失败: {e}')
            return {}

    # ───── 文件导入 ─────

    async def import_document(self, file_path: str) -> dict:
        """POST /ai/import multipart → 导入景点知识文档"""
        try:
            ext = Path(file_path).suffix.lower()
            mime = MIME_MAP.get(ext, 'application/octet-stream')
            with open(file_path, 'rb') as f:
                files = {'file': (file_path, f, mime)}
                resp = await self.client.post(f'{self.base_url}/ai/import', files=files)
            return resp.json()
        except Exception as e:
            print(f'[AiClient] import_document 失败: {e}')
            return {'code': 500, 'success': False, 'message': str(e), 'data': None}

ai_client = AiServiceClient()
