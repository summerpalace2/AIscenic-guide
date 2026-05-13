import uuid
from datetime import datetime, timezone
from sqlalchemy import Column, String, DateTime, Text, Integer
from sqlalchemy.dialects.postgresql import UUID
from app.db.base import Base

class DialogSession(Base):
    __tablename__ = "dialog_sessions"
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    session_id = Column(String(100), unique=True, nullable=False, index=True)
    user_id = Column(UUID(as_uuid=True), nullable=True, index=True)
    title = Column(String(200), default="New Dialog")
    message_count = Column(Integer, default=0)
    is_active = Column(Integer, default=1)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))

class DialogMessage(Base):
    __tablename__ = "dialog_messages"
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    message_id = Column(String(100), unique=True, nullable=False)
    session_id = Column(String(100), nullable=False, index=True)
    role = Column(String(20), nullable=False)
    content = Column(Text, nullable=False)
    audio_url = Column(String(500), default="")
    emotion = Column(String(50), default="")
    intent = Column(String(50), default="")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
