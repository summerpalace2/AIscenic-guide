import uuid
from datetime import datetime, timezone
from sqlalchemy import Column, String, DateTime, Text, Integer as Int
from sqlalchemy import Uuid
from sqlalchemy import JSON
from app.db.base import Base

class KnowledgeDocument(Base):
    __tablename__ = "knowledge_documents"
    id = Column(Uuid(), primary_key=True, default=uuid.uuid4)
    title = Column(String(200), nullable=False, index=True)
    category = Column(String(50), nullable=False, index=True)
    content = Column(Text, default="")
    file_url = Column(String(500), default="")
    file_md5 = Column(String(64), default="", index=True)
    tags = Column(JSON, default=list)
    status = Column(String(20), default="published")
    vector_status = Column(String(20), default="pending")
    chunk_count = Column(Int, default=0)
    created_by = Column(Uuid(), nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))
