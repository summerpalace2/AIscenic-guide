"""
用户模块测试
"""
import base64
import json


def _user_id_from_token(token):
    """从 JWT token 中解码出 user_id（sub 字段），不验签"""
    try:
        payload = token.split('.')[1]
        padding = 4 - len(payload) % 4
        if padding != 4:
            payload += '=' * padding
        decoded = base64.urlsafe_b64decode(payload)
        return json.loads(decoded).get('sub')
    except Exception:
        return None


class TestUsers:
    """用户信息接口测试"""

    def test_get_user(self, client, auth_token):
        """通过 user_id 获取用户应返回完整信息"""
        user_id = _user_id_from_token(auth_token)
        assert user_id, '无法从 token 解码 user_id'
        headers = {'Authorization': f'Bearer {auth_token}'}
        resp = client.get(f'/api/v1/users/{user_id}', headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert data['data']['user_id'] == user_id
        assert 'nickname' in data['data']
        assert 'interests' in data['data']
        assert 'role' in data['data']

    def test_get_user_nonexistent(self, client, auth_token):
        """不存在用户应返回 404"""
        headers = {'Authorization': f'Bearer {auth_token}'}
        resp = client.get('/api/v1/users/00000000-0000-0000-0000-000000000000', headers=headers)
        assert resp.status_code == 404
        assert resp.json()['success'] is False

    def test_get_user_no_auth(self, client):
        """未认证不可获取用户信息"""
        resp = client.get('/api/v1/users/00000000-0000-0000-0000-000000000000')
        assert resp.status_code == 401

    def test_update_interests(self, client, auth_token):
        """更新兴趣偏好应返回完整用户信息"""
        user_id = _user_id_from_token(auth_token)
        assert user_id
        headers = {'Authorization': f'Bearer {auth_token}'}
        interests = ['历史', '自然']
        resp = client.put(f'/api/v1/users/{user_id}/interests',
                          json={'interests': interests}, headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert data['data']['interests'] == interests
        assert data['data']['user_id'] == user_id

    def test_update_interests_nonexistent(self, client, auth_token):
        """不存在的用户更新兴趣应返回 404"""
        headers = {'Authorization': f'Bearer {auth_token}'}
        resp = client.put('/api/v1/users/00000000-0000-0000-0000-000000000000/interests',
                          json={'interests': ['历史']}, headers=headers)
        assert resp.status_code == 404

    def test_update_interests_no_auth(self, client):
        """未认证不可更新兴趣"""
        resp = client.put('/api/v1/users/00000000-0000-0000-0000-000000000000/interests',
                          json={'interests': ['历史']})
        assert resp.status_code == 401

    def test_update_profile_nickname(self, client, auth_token):
        """更新用户昵称"""
        user_id = _user_id_from_token(auth_token)
        assert user_id
        headers = {'Authorization': f'Bearer {auth_token}'}
        resp = client.put(f'/api/v1/users/{user_id}/profile',
                          json={'nickname': '新昵称'}, headers=headers)
        assert resp.status_code == 200
        assert resp.json()['success'] is True

    def test_update_profile_avatar(self, client, auth_token):
        """更新用户头像"""
        user_id = _user_id_from_token(auth_token)
        assert user_id
        headers = {'Authorization': f'Bearer {auth_token}'}
        resp = client.put(f'/api/v1/users/{user_id}/profile',
                          json={'avatar': 'http://example.com/avatar.png'}, headers=headers)
        assert resp.status_code == 200
        assert resp.json()['success'] is True

    def test_update_profile_no_auth(self, client):
        """未认证不可更新资料"""
        resp = client.put('/api/v1/users/00000000-0000-0000-0000-000000000000/profile',
                          json={'nickname': 'test'})
        assert resp.status_code == 401
