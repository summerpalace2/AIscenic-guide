"""
数字人配置持久化服务
从 DigitalHumanConfig 表读写数字人的外观/语音/情感风格配置
"""
import uuid
from datetime import datetime, timezone
from sqlalchemy.orm import Session
from app.models.system import DigitalHumanConfig

# 默认数字人配置
DEFAULT_CONFIG = {
    'appearance': {'model_id': 'model_female_01', 'outfit': 'traditional', 'hairstyle': 'long'},
    'voice': {'voice_id': 'voice_warm_01', 'speed': 1.0, 'pitch': 1.0, 'volume': 1.0},
    'emotion_style': 'friendly',
    'preview_url': ''
}


class DigitalHumanConfigService:
    """数字人配置服务，从数据库持久化读写"""

    def get_config(self, db: Session) -> dict:
        """获取数字人配置，若数据库无数据则初始化默认配置"""
        config = db.query(DigitalHumanConfig).filter(
            DigitalHumanConfig.config_type == 'main'
        ).first()
        if not config:
            return self._init_default(db)
        return config.config_data

    def update_config(self, db: Session, updates: dict) -> dict:
        """更新数字人配置的指定字段"""
        config = db.query(DigitalHumanConfig).filter(
            DigitalHumanConfig.config_type == 'main'
        ).first()
        if not config:
            config = DigitalHumanConfig(
                id=uuid.uuid4(),
                config_type='main',
                config_data=DEFAULT_CONFIG.copy()
            )
            db.add(config)
        current = config.config_data.copy()
        for k, v in updates.items():
            if v is not None:
                current[k] = v
        config.config_data = current
        config.updated_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(config)
        return config.config_data

    def delete_config(self, db: Session) -> bool:
        """删除数字人配置，恢复到默认"""
        config = db.query(DigitalHumanConfig).filter(
            DigitalHumanConfig.config_type == 'main'
        ).first()
        if not config:
            return False
        db.delete(config)
        db.commit()
        return True

    def _init_default(self, db: Session) -> dict:
        """初始化默认配置到数据库"""
        config = DigitalHumanConfig(
            id=uuid.uuid4(),
            config_type='main',
            config_data=DEFAULT_CONFIG.copy()
        )
        db.add(config)
        db.commit()
        db.refresh(config)
        return config.config_data


dh_config_service = DigitalHumanConfigService()
