import uuid
from datetime import datetime, timezone
from sqlalchemy import Column, String, DateTime, Text, Integer, Float
from sqlalchemy import Uuid, JSON
from app.db.base import Base

class AnalyticsReport(Base):
    __tablename__ = "analytics_reports"
    id = Column(Uuid(), primary_key=True, default=uuid.uuid4)
    title = Column(String(200), nullable=False)
    type = Column(String(50), default="daily")
    period_start = Column(DateTime(timezone=True), nullable=False)
    period_end = Column(DateTime(timezone=True), nullable=False)
    status = Column(String(20), default="completed")
    data = Column(JSON, default={})
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))

class ServiceLog(Base):
    __tablename__ = "service_logs"
    id = Column(Uuid(), primary_key=True, default=uuid.uuid4)
    session_id = Column(String(100), nullable=False, index=True)
    user_id = Column(Uuid(), nullable=True)
    question = Column(Text, default="")
    emotion = Column(String(50), default="")
    intent = Column(String(50), default="")
    response_time_ms = Column(Integer, default=0)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
