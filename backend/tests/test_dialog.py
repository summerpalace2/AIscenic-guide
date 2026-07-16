"""
对话模块测试
"""

class TestDialog:
    def test_send_message_no_auth(self, client):
        """未认证不可发送消息"""
        resp = client.post('/api/v1/dialog/message', json={'content': 'hello'})
        assert resp.status_code == 401

    def test_send_message_with_auth(self, client, auth_token):
        """认证后发送消息成功"""
        resp = client.post('/api/v1/dialog/message', json={'content': '故宫有什么景点？'},
                           headers={'Authorization': f'Bearer {auth_token}'})
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert 'session_id' in data['data']
        assert 'reply' in data['data']

    def test_get_sessions(self, client, auth_token):
        """获取会话列表"""
        client.post('/api/v1/dialog/message', json={'content': 'test'},
                    headers={'Authorization': f'Bearer {auth_token}'})
        resp = client.get('/api/v1/dialog/sessions',
                          headers={'Authorization': f'Bearer {auth_token}'})
        assert resp.status_code == 200
        assert resp.json()['success'] is True
