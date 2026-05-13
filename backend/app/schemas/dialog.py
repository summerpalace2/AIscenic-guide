from datetime import datetime
from typing import Optional
from pydantic import BaseModel

class MessageRequest(BaseModel):
    content: str
    session_id: Optional[str] = None
    scenic_spot_id: Optional[str] = None

class MessageResponse(BaseModel):
    message_id: str
    session_id: str
    reply: str
    emotion: str = ""
    intent: str = ""
    audio_url: str = ""
    duration_ms: int = 0

class SessionItem(BaseModel):
    session_id: str
    title: str
    message_count: int
    last_message: str
    last_time: Optional[datetime] = None

class MessageItem(BaseModel):
    message_id: str
    role: str
    content: str
    audio_url: Optional[str] = None
    emotion: Optional[str] = None
    created_at: Optional[datetime] = None

class MessageHistory(BaseModel):
    session_id: str
    total: int
    has_more: bool = False
    items: list[MessageItem] = []
