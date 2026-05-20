"""
WebSocket 连接管理器
管理用户连接的生命周期：建立/断开/心跳
"""
from fastapi import WebSocket


class ConnectionManager:
    """管理所有 WebSocket 连接，一个用户可能多个设备同时在线"""

    def __init__(self):
        self._connections: dict[str, list[WebSocket]] = {}
        self._reverse: dict[int, str] = {}

    async def connect(self, ws: WebSocket, user_id: str):
        await ws.accept()
        self._connections.setdefault(user_id, []).append(ws)
        self._reverse[id(ws)] = user_id

    async def disconnect(self, ws: WebSocket):
        uid = self._reverse.pop(id(ws), None)
        if uid and uid in self._connections:
            try:
                self._connections[uid].remove(ws)
            except ValueError:
                pass
            if not self._connections[uid]:
                del self._connections[uid]

    async def send_json(self, ws: WebSocket, data: dict):
        try:
            await ws.send_json(data)
        except Exception:
            await self.disconnect(ws)

    async def broadcast_to_user(self, user_id: str, data: dict):
        for ws in self._connections.get(user_id, []):
            await self.send_json(ws, data)

    @property
    def online_count(self) -> int:
        return len(self._connections)


ws_manager = ConnectionManager()
