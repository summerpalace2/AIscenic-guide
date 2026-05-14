"""
WebSocket 消息路由处理
"""
import json
from fastapi import WebSocket, WebSocketDisconnect
from sqlalchemy.orm import Session
from app.ws.connection_manager import ws_manager
from app.services.dialog_service import dialog_service


async def handle_message(ws: WebSocket, data: dict, user_id: str, db: Session):
    msg_type = data.get('type', '')
    if msg_type == 'chat':
        result = await dialog_service.send_message(
            db=db,
            content=data.get('content', ''),
            session_id=data.get('session_id'),
            user_id=user_id
        )
        reply = result.get('reply', '')
        session_id = result.get('session_id', '')
        # 分片发送
        chunk_size = 20
        for i in range(0, len(reply), chunk_size):
            await ws_manager.send_json(ws, {
                'type': 'chunk',
                'content': reply[i:i+chunk_size],
                'index': i // chunk_size
            })
        await ws_manager.send_json(ws, {'type': 'emotion', 'label': 'friendly'})
        await ws_manager.send_json(ws, {'type': 'done', 'session_id': session_id})
    elif msg_type == 'ping':
        await ws_manager.send_json(ws, {'type': 'pong'})


async def handle_websocket(ws: WebSocket, user_id: str, db: Session):
    await ws_manager.connect(ws, user_id)
    try:
        while True:
            raw = await ws.receive_text()
            data = json.loads(raw)
            await handle_message(ws, data, user_id, db)
    except (WebSocketDisconnect, Exception):
        await ws_manager.disconnect(ws)
