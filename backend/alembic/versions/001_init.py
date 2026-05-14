"""init

Revision ID: 001
Revises:
Create Date: 2026-05-14
"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID, JSON
import uuid

revision: str = '001'
down_revision: Union[str, None] = None


def upgrade() -> None:
    # 用户表
    op.create_table('users',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, default=uuid.uuid4),
        sa.Column('phone', sa.String(20), unique=True, nullable=True),
        sa.Column('password_hash', sa.String(128), nullable=False),
        sa.Column('nickname', sa.String(50), server_default=''),
        sa.Column('avatar', sa.String(500), server_default=''),
        sa.Column('role', sa.String(20), server_default='tourist'),
        sa.Column('interests', JSON, server_default='[]'),
        sa.Column('wechat_openid', sa.String(100), unique=True, nullable=True),
        sa.Column('is_active', sa.Boolean, server_default='true'),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index('ix_users_phone', 'users', ['phone'])

    # 知识文档表
    op.create_table('knowledge_documents',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, default=uuid.uuid4),
        sa.Column('title', sa.String(200), nullable=False),
        sa.Column('category', sa.String(50), nullable=False),
        sa.Column('content', sa.Text, server_default=''),
        sa.Column('file_url', sa.String(500), server_default=''),
        sa.Column('tags', JSON, server_default='[]'),
        sa.Column('status', sa.String(20), server_default='published'),
        sa.Column('vector_status', sa.String(20), server_default='pending'),
        sa.Column('created_by', UUID(as_uuid=True), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index('ix_knowledge_documents_title', 'knowledge_documents', ['title'])
    op.create_index('ix_knowledge_documents_category', 'knowledge_documents', ['category'])

    # 对话会话表
    op.create_table('dialog_sessions',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, default=uuid.uuid4),
        sa.Column('session_id', sa.String(100), unique=True, nullable=False),
        sa.Column('user_id', UUID(as_uuid=True), nullable=True),
        sa.Column('title', sa.String(200), server_default='New Dialog'),
        sa.Column('message_count', sa.Integer, server_default='0'),
        sa.Column('is_active', sa.Integer, server_default='1'),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index('ix_dialog_sessions_session_id', 'dialog_sessions', ['session_id'])
    op.create_index('ix_dialog_sessions_user_id', 'dialog_sessions', ['user_id'])

    # 对话消息表
    op.create_table('dialog_messages',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, default=uuid.uuid4),
        sa.Column('message_id', sa.String(100), unique=True, nullable=False),
        sa.Column('session_id', sa.String(100), nullable=False),
        sa.Column('role', sa.String(20), nullable=False),
        sa.Column('content', sa.Text, nullable=False),
        sa.Column('audio_url', sa.String(500), server_default=''),
        sa.Column('emotion', sa.String(50), server_default=''),
        sa.Column('intent', sa.String(50), server_default=''),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index('ix_dialog_messages_session_id', 'dialog_messages', ['session_id'])

    # 景点表
    op.create_table('scenic_spots',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, default=uuid.uuid4),
        sa.Column('name', sa.String(100), nullable=False),
        sa.Column('category', sa.String(50), server_default='history'),
        sa.Column('description', sa.Text, server_default=''),
        sa.Column('longitude', sa.Float, server_default='0.0'),
        sa.Column('latitude', sa.Float, server_default='0.0'),
        sa.Column('audio_url', sa.String(500), server_default=''),
        sa.Column('tags', JSON, server_default='[]'),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index('ix_scenic_spots_name', 'scenic_spots', ['name'])

    # 服务日志表
    op.create_table('service_logs',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, default=uuid.uuid4),
        sa.Column('session_id', sa.String(100), nullable=False),
        sa.Column('user_id', UUID(as_uuid=True), nullable=True),
        sa.Column('question', sa.Text, server_default=''),
        sa.Column('emotion', sa.String(50), server_default=''),
        sa.Column('intent', sa.String(50), server_default=''),
        sa.Column('response_time_ms', sa.Integer, server_default='0'),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index('ix_service_logs_session_id', 'service_logs', ['session_id'])

    # 分析报表表
    op.create_table('analytics_reports',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, default=uuid.uuid4),
        sa.Column('title', sa.String(200), nullable=False),
        sa.Column('type', sa.String(50), server_default='daily'),
        sa.Column('period_start', sa.DateTime(timezone=True), nullable=False),
        sa.Column('period_end', sa.DateTime(timezone=True), nullable=False),
        sa.Column('status', sa.String(20), server_default='completed'),
        sa.Column('data', JSON, server_default='{}'),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )

    # 系统设置表
    op.create_table('system_settings',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, default=uuid.uuid4),
        sa.Column('key', sa.String(100), unique=True, nullable=False),
        sa.Column('value', sa.Text, server_default=''),
    )

    # 管理员日志表
    op.create_table('admin_logs',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, default=uuid.uuid4),
        sa.Column('admin_id', UUID(as_uuid=True), nullable=True),
        sa.Column('action', sa.String(100), nullable=False),
        sa.Column('target', sa.String(200), server_default=''),
        sa.Column('detail', sa.Text, server_default=''),
        sa.Column('ip', sa.String(50), server_default=''),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )

    # 数字人配置表
    op.create_table('digital_human_config',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, default=uuid.uuid4),
        sa.Column('config_type', sa.String(50), server_default='main'),
        sa.Column('config_data', JSON, server_default='{}'),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )


def downgrade() -> None:
    op.drop_table('digital_human_config')
    op.drop_table('admin_logs')
    op.drop_table('system_settings')
    op.drop_table('analytics_reports')
    op.drop_table('service_logs')
    op.drop_table('scenic_spots')
    op.drop_table('dialog_messages')
    op.drop_table('dialog_sessions')
    op.drop_table('knowledge_documents')
    op.drop_table('users')
