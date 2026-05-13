import uuid
from datetime import datetime, timezone
from sqlalchemy import Column, String, DateTime, Boolean, Text
from sqlalchemy.dialects.postgresql import UUID, JSON
from app.db.base import Base

class SystemSettings(Base):
    __tablename__ = "system_settings"
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    key = Column(String(100), unique=True, nullable=False)
    value = Column(Text, default="")

class AdminLog(Base):
    __tablename__ = "admin_logs"
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    admin_id = Column(UUID(as_uuid=True), nullable=True)
    action = Column(String(100), nullable=False)
    target = Column(String(200), default="")
    detail = Column(Text, default="")
    ip = Column(String(50), default="")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))

class DigitalHumanConfig(Base):
    __tablename__ = "digital_human_config"
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    config_type = Column(String(50), default="main")
    config_data = Column(JSON, default={})
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))
