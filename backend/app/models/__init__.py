# 所有 Model 类导入在此注册（供 Alembic 和 Base.metadata.create_all 发现）
from .user import User  # noqa: F401
from .knowledge import KnowledgeDocument  # noqa: F401
from .dialog import DialogSession, DialogMessage  # noqa: F401
from .scenic_spot import ScenicSpot  # noqa: F401
from .analytics import AnalyticsReport, ServiceLog  # noqa: F401
from .system import SystemSettings, AdminLog, DigitalHumanConfig  # noqa: F401
