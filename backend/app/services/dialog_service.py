import uuid
from datetime import datetime, timezone
from sqlalchemy.orm import Session
from app.models.dialog import DialogSession, DialogMessage
from app.services.ai_client import ai_client


def _format_scenic_reply(data: dict) -> str:
    """将 ScenicResponse 格式化为纯文本 Markdown"""
    parts = []
    if data.get('welcomeMessage'):
        parts.append(data['welcomeMessage'])
    for item in data.get('dataList', []):
        parts.append(f"### {item.get('name', '')}")
        if item.get('price'):
            parts.append(f"- **价格**：{item['price']}")
        if item.get('description'):
            parts.append(f"- **特色**：{item['description']}")
        if item.get('location'):
            parts.append(f"- **位置**：{item['location']}")
    if data.get('closingTips'):
        parts.append(data['closingTips'])
    return '\n\n'.join(parts)


class DialogService:
    async def send_message(self, db: Session, content: str, session_id=None, user_id=None, scenic_spot_id=None) -> dict:
        if not session_id:
            session_id = f'sess_{uuid.uuid4().hex[:12]}'
            session = DialogSession(id=uuid.uuid4(), session_id=session_id, user_id=uuid.UUID(user_id) if user_id else None, title=content[:50])
            db.add(session)
            db.commit()
        else:
            session = db.query(DialogSession).filter(DialogSession.session_id == session_id, DialogSession.is_active == 1).first()

        # 调 AI 端获取真实回复
        ai_resp = await ai_client.chat_structured(content, session_id)
        if ai_resp.get('success') and ai_resp.get('data'):
            data = ai_resp['data']
            reply = _format_scenic_reply(data)
            emotion = 'friendly'
        else:
            # AI 端不可用时的降级回复
            reply = ('灵山智慧导游暂时无法连接到 AI 服务，请稍后再试。'
                     f'\n\n您的问题：{content}')
            emotion = 'neutral'

        um = DialogMessage(id=uuid.uuid4(), message_id=f'msg_{uuid.uuid4().hex[:12]}', session_id=session_id, role='user', content=content, created_at=datetime.now(timezone.utc))
        am = DialogMessage(id=uuid.uuid4(), message_id=f'msg_{uuid.uuid4().hex[:12]}', session_id=session_id, role='assistant', content=reply, created_at=datetime.now(timezone.utc))
        db.add_all([um, am])
        if session:
            session.message_count = (session.message_count or 0) + 2
            session.updated_at = datetime.now(timezone.utc)
        db.commit()
        return {'message_id': am.message_id, 'session_id': session_id, 'reply': reply, 'emotion': emotion, 'intent': 'qa', 'audio_url': '', 'duration_ms': 0}

    def get_sessions(self, db: Session, user_id=None, page=1, size=20) -> dict:
        q = db.query(DialogSession).filter(DialogSession.is_active == 1)
        if user_id:
            q = q.filter(DialogSession.user_id == uuid.UUID(user_id))
        total = q.count()
        items = q.order_by(DialogSession.updated_at.desc()).offset((page-1)*size).limit(size).all()
        result_items = []
        for s in items:
            last_msg = db.query(DialogMessage).filter(
                DialogMessage.session_id == s.session_id
            ).order_by(DialogMessage.created_at.desc()).first()
            result_items.append({
                'session_id': s.session_id,
                'title': s.title,
                'message_count': s.message_count,
                'last_message': last_msg.content if last_msg else '',
                'last_time': s.updated_at
            })
        return {'total': total, 'items': result_items}

    def get_messages(self, db: Session, session_id: str, before_id=None, limit=50) -> dict:
        q = db.query(DialogMessage).filter(DialogMessage.session_id == session_id)
        if before_id:
            bm = db.query(DialogMessage).filter(DialogMessage.message_id == before_id).first()
            if bm:
                q = q.filter(DialogMessage.created_at < bm.created_at)
        total = q.count()
        items = q.order_by(DialogMessage.created_at.desc()).limit(limit).all()
        items.reverse()
        return {'session_id': session_id, 'total': total, 'has_more': total > limit, 'items': [{'message_id': m.message_id, 'role': m.role, 'content': m.content, 'audio_url': m.audio_url, 'emotion': m.emotion, 'created_at': m.created_at} for m in items]}

    def delete_session(self, db: Session, session_id: str):
        s = db.query(DialogSession).filter(DialogSession.session_id == session_id).first()
        if s:
            s.is_active = 0
            db.commit()

dialog_service = DialogService()
