"""
pytest 测试配置
提供 TestClient、测试数据库会话、认证 token 等公共夹具
"""
import os
from pathlib import Path

# 测试数据库文件放在 conftest.py 同级目录，确保容器内外均可写
_test_db_dir = Path(__file__).parent
_test_db_path = str(_test_db_dir / 'test.db')
os.environ['DATABASE_URL'] = f'sqlite:///{_test_db_path}'

# 以下 import 必须放在 env 设置之后，不能提前
import uuid  # noqa: E402
import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402
from app.main import app  # noqa: E402
from app.db.base import Base  # noqa: E402
from app.db.session import get_db  # noqa: E402

engine = create_engine(f'sqlite:///{_test_db_path}', connect_args={'check_same_thread': False})
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


@pytest.fixture
def auth_token(client):
    """注册并登录，返回 JWT token"""
    phone = f'138{hash(str(uuid.uuid4())) % 100000000:08d}'
    client.post('/api/v1/auth/register', json={
        'phone': phone, 'password': 'test123456', 'nickname': 'tester'
    })
    resp = client.post('/api/v1/auth/login', json={
        'phone': phone, 'password': 'test123456'
    })
    return resp.json()['data']['token']

@pytest.fixture
def db_session():
    """提供测试数据库会话，用于直接操作数据库"""
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()

@pytest.fixture
def admin_token(client):
    """创建管理员并登录，返回 JWT token"""
    from app.core.security import get_password_hash
    from app.models.user import User
    admin_id = uuid.uuid4()
    admin = User(
        id=admin_id,
        phone='13900000001',
        password_hash=get_password_hash('admin123'),
        nickname='test_admin',
        role='admin'
    )
    db = TestingSessionLocal()
    db.add(admin)
    db.commit()
    db.close()

    resp = client.post('/api/v1/auth/login', json={
        'phone': '13900000001', 'password': 'admin123'
    })
    return resp.json()['data']['token']
