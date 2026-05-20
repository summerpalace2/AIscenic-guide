"""
位置服务模块测试
"""

class TestLocation:
    def test_list_spots(self, client):
        """景点列表不需要认证"""
        resp = client.get('/api/v1/location/scenic-spots')
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True

    def test_nearby(self, client):
        """附近景点查询"""
        resp = client.get('/api/v1/location/nearby',
                          params={'longitude': 116.397, 'latitude': 39.916, 'radius': 1000})
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
