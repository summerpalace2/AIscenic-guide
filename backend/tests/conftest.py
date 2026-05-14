"""
pytest 测试配置
提供 TestClient、测试数据库会话等公共夹具

注意：在导入 app 前设置 DATABASE_URL 环境变量，
确保测试使用 SQLite 内存数据库而非 PostgreSQL。
"""
import os
os.environ['DATABASE_URL'] = 'sqlite:///./test.db'

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from app.main import app
from app.db.base import Base
from app.db.session import get_db

# 使用 SQLite 内存数据库进行测试
engine = create_engine('sqlite:///./test.db', connect_args={'check_same_thread': False})
TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def override_get_db():
    """覆盖依赖注入中的 get_db，使用测试数据库"""
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture(autouse=True)
def setup_db():
    """每个测试前重建表结构"""
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)


@pytest.fixture
def client():
    """提供测试 HTTP 客户端"""
    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()
