"""
认证模块测试
"""


class TestAuth:
    """认证接口测试"""

    def test_register(self, client):
        """测试游客注册"""
        resp = client.post('/api/v1/auth/register', json={
            'phone': '13800138000',
            'password': 'test123456',
            'nickname': '测试用户'
        })
        assert resp.status_code == 201
        data = resp.json()
        assert data['success'] is True
        assert 'token' in data['data']
        assert 'user_id' in data['data']

    def test_register_duplicate_phone(self, client):
        """测试重复手机号注册"""
        client.post('/api/v1/auth/register', json={
            'phone': '13800138000', 'password': 'test123456'
        })
        resp = client.post('/api/v1/auth/register', json={
            'phone': '13800138000', 'password': 'test123456'
        })
        assert resp.status_code == 400

    def test_login(self, client):
        """测试登录"""
        client.post('/api/v1/auth/register', json={
            'phone': '13800138001', 'password': 'test123456'
        })
        resp = client.post('/api/v1/auth/login', json={
            'phone': '13800138001', 'password': 'test123456'
        })
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert 'token' in data['data']

    def test_login_wrong_password(self, client):
        """测试错误密码登录"""
        client.post('/api/v1/auth/register', json={
            'phone': '13800138002', 'password': 'test123456'
        })
        resp = client.post('/api/v1/auth/login', json={
            'phone': '13800138002', 'password': 'wrongpass'
        })
        # 后端对错误密码返回 400（ValueError 统一处理），而非 401
        assert resp.status_code == 400
