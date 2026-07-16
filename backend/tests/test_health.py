"""
健康检查接口测试
"""


class TestHealth:
    """健康检查测试"""

    def test_health_check(self, client):
        """测试 /health 端点"""
        resp = client.get('/health')
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert data['data'] == 'ok'
