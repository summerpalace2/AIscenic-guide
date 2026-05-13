import uuid
from datetime import datetime, timezone
from sqlalchemy import Column, String, DateTime, Float, Text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy import JSON
from app.db.base import Base

class ScenicSpot(Base):
    __tablename__ = "scenic_spots"
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(100), nullable=False, index=True)
    category = Column(String(50), default="history")
    description = Column(Text, default="")
    longitude = Column(Float, default=0.0)
    latitude = Column(Float, default=0.0)
    audio_url = Column(String(500), default="")
    tags = Column(JSON, default=list)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))
