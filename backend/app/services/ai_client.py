import httpx
from app.core.config import get_settings

settings = get_settings()

class AiServiceClient:
    def __init__(self, base_url=None):
        self.base_url = base_url or settings.AI_SERVICE_URL
        self.client = httpx.AsyncClient(timeout=30.0)

    async def close(self):
        await self.client.aclose()

    async def query_knowledge(self, query: str) -> str:
        try:
            resp = await self.client.post(f'{self.base_url}/ai/rag/query', params={'query': query})
            result = resp.json()
            return result.get('data', '') if result.get('success') else ''
        except Exception as e:
            print(f'[AiClient] RAG query failed: {e}')
            return ''

    async def extract_slots(self, session_id: str, message: str):
        try:
            await self.client.post(f'{self.base_url}/ai/slots/extract', params={'sessionId': session_id, 'message': message})
        except Exception as e:
            print(f'[AiClient] Slot extract failed: {e}')

    async def get_slots(self, session_id: str) -> dict:
        try:
            resp = await self.client.get(f'{self.base_url}/ai/slots/{session_id}')
            return resp.json().get('data', {})
        except Exception:
            return {}

    async def get_chat_memory(self, session_id: str, last_n: int = 20) -> list:
        try:
            resp = await self.client.get(f'{self.base_url}/ai/memory/{session_id}', params={'lastN': last_n})
            return resp.json().get('data', [])
        except Exception:
            return []

    async def add_chat_memory(self, session_id: str, messages: list):
        try:
            await self.client.post(f'{self.base_url}/ai/memory/add', json={'sessionId': session_id, 'messages': messages})
        except Exception as e:
            print(f'[AiClient] Add memory failed: {e}')

ai_client = AiServiceClient()
