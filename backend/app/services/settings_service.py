"""
系统设置持久化服务
从 SystemSettings 表读写系统配置（景区名称、营业时间等）
"""
import uuid
from typing import Any
from sqlalchemy.orm import Session
from app.models.system import SystemSettings

# 默认系统设置
DEFAULT_SETTINGS = {
    'scenic_name': '故宫博物院',
    'business_hours': '08:30-17:00',
    'welcome_message': '欢迎来到故宫博物院！',
    'maintenance_mode': 'false',
    'language': 'zh-CN'
}

# 需要保持类型的键（非字符串类型）
BOOLEAN_KEYS = {'maintenance_mode'}


class SettingsService:
    """系统设置服务，从数据库持久化读写"""

    def get_all(self, db: Session) -> dict:
        """获取所有系统设置，若数据库无数据则初始化默认值"""
        settings = {}
        rows = db.query(SystemSettings).all()
        if not rows:
            return self._init_defaults(db)
        for row in rows:
            val = row.value
            if row.key in BOOLEAN_KEYS:
                val = val.lower() == 'true'
            settings[row.key] = val
        return settings

    def update(self, db: Session, updates: dict) -> dict:
        """更新系统设置的指定字段"""
        for key, value in updates.items():
            if value is None:
                continue
            # 布尔值转为字符串存储
            if isinstance(value, bool):
                value = str(value).lower()
            row = db.query(SystemSettings).filter(
                SystemSettings.key == key
            ).first()
            if row:
                row.value = str(value)
            else:
                row = SystemSettings(id=uuid.uuid4(), key=key, value=str(value))
                db.add(row)
        db.commit()
        return self.get_all(db)

    def _init_defaults(self, db: Session) -> dict:
        """初始化默认设置到数据库"""
        result = {}
        for key, value in DEFAULT_SETTINGS.items():
            row = SystemSettings(id=uuid.uuid4(), key=key, value=str(value))
            db.add(row)
            result[key] = value
        db.commit()
        return result


settings_service = SettingsService()
