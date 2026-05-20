import uuid
from datetime import datetime, timezone
from typing import Optional
from sqlalchemy.orm import Session
from app.models.dialog import DialogSession, DialogMessage
from app.services.ai_client import ai_client

SEP = chr(92)+'n---'+chr(92)+'n'

class DialogService:
    async def send_message(self, db: Session, content: str, session_id=None, user_id=None, scenic_spot_id=None) -> dict:
        if not session_id:
            session_id = f'sess_{uuid.uuid4().hex[:12]}'
            session = DialogSession(id=uuid.uuid4(), session_id=session_id, user_id=uuid.UUID(user_id) if user_id else None, title=content[:50])
            db.add(session); db.commit()
        else:
            session = db.query(DialogSession).filter(DialogSession.session_id == session_id, DialogSession.is_active == 1).first()
        await ai_client.extract_slots(session_id, content)
        knowledge = await ai_client.query_knowledge(content)
        await ai_client.add_chat_memory(session_id, [{'type': 'user', 'content': content}])
        reply = self._build_reply(content, knowledge)
        um = DialogMessage(id=uuid.uuid4(), message_id=f'msg_{uuid.uuid4().hex[:12]}', session_id=session_id, role='user', content=content, created_at=datetime.now(timezone.utc))
        am = DialogMessage(id=uuid.uuid4(), message_id=f'msg_{uuid.uuid4().hex[:12]}', session_id=session_id, role='assistant', content=reply, created_at=datetime.now(timezone.utc))
        db.add_all([um, am])
        await ai_client.add_chat_memory(session_id, [{'type': 'assistant', 'content': reply}])
        if session:
            session.message_count = (session.message_count or 0) + 2
            session.updated_at = datetime.now(timezone.utc)
        db.commit()
        return {'message_id': am.message_id, 'session_id': session_id, 'reply': reply, 'emotion': 'friendly', 'intent': 'qa', 'audio_url': '', 'duration_ms': 0}

    def _build_reply(self, msg: str, knowledge: str) -> str:
        if not knowledge:
            return f'Hello! I am your scenic guide. About [{msg}], I am still learning. Please tell me more!'
        parts = knowledge.split(SEP)
        reply = 'Here is what I found:'
        for i, p in enumerate(parts[:3], 1):
            reply += chr(10) + chr(10) + f'### {i}. {p[:60]}...' + chr(10) + p
        reply += chr(10) + chr(10) + 'You might also ask:'
        return reply

    def get_sessions(self, db: Session, user_id=None, page=1, size=20) -> dict:
        q = db.query(DialogSession).filter(DialogSession.is_active == 1)
        if user_id: q = q.filter(DialogSession.user_id == uuid.UUID(user_id))
        total = q.count()
        items = q.order_by(DialogSession.updated_at.desc()).offset((page-1)*size).limit(size).all()
        return {'total': total, 'items': [{'session_id': s.session_id, 'title': s.title, 'message_count': s.message_count, 'last_message': '', 'last_time': s.updated_at} for s in items]}

    def get_messages(self, db: Session, session_id: str, before_id=None, limit=50) -> dict:
        q = db.query(DialogMessage).filter(DialogMessage.session_id == session_id)
        if before_id:
            bm = db.query(DialogMessage).filter(DialogMessage.message_id == before_id).first()
            if bm: q = q.filter(DialogMessage.created_at < bm.created_at)
        total = q.count()
        items = q.order_by(DialogMessage.created_at.desc()).limit(limit).all()
        items.reverse()
        return {'session_id': session_id, 'total': total, 'has_more': total > limit, 'items': [{'message_id': m.message_id, 'role': m.role, 'content': m.content, 'audio_url': m.audio_url, 'emotion': m.emotion, 'created_at': m.created_at} for m in items]}

    def delete_session(self, db: Session, session_id: str):
        s = db.query(DialogSession).filter(DialogSession.session_id == session_id).first()
        if s: s.is_active = 0; db.commit()

dialog_service = DialogService()
