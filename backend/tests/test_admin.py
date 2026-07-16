"""
管理后台模块测试
"""


class TestAdmin:
    """管理后台接口测试"""

    def test_list_admins(self, client, admin_token):
        """管理员列表"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/admin/users', headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert 'items' in data['data']
        # 至少包含测试管理员自己
        assert len(data['data']['items']) >= 1

    def test_list_admins_no_auth(self, client):
        """未认证不可查看管理员列表"""
        resp = client.get('/api/v1/admin/users')
        assert resp.status_code == 401

    def test_list_admins_forbidden(self, client, auth_token):
        """普通用户不可查看管理员列表"""
        headers = {'Authorization': f'Bearer {auth_token}'}
        resp = client.get('/api/v1/admin/users', headers=headers)
        assert resp.status_code == 403

    def test_create_admin(self, client, admin_token):
        """创建新管理员"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        import uuid
        username = f'newadmin_{uuid.uuid4().hex[:6]}'
        resp = client.post('/api/v1/admin/users',
                           json={'username': username, 'password': 'test123456', 'role': 'admin'},
                           headers=headers)
        assert resp.status_code == 201
        assert resp.json()['success'] is True

        # 验证已创建
        list_resp = client.get('/api/v1/admin/users', headers=headers)
        usernames = [u['username'] for u in list_resp.json()['data']['items']]
        assert username in usernames

    def test_create_duplicate_username(self, client, admin_token):
        """重复用户名应返回 400"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        # 使用已存在的 admin 用户名
        client.post('/api/v1/admin/users',
                    json={'username': 'dupadmin', 'password': 'test123456', 'role': 'admin'},
                    headers=headers)
        resp = client.post('/api/v1/admin/users',
                           json={'username': 'dupadmin', 'password': 'test123456', 'role': 'admin'},
                           headers=headers)
        assert resp.status_code == 400
        assert resp.json()['success'] is False

    def test_create_admin_no_auth(self, client):
        """未认证不可创建管理员"""
        resp = client.post('/api/v1/admin/users',
                           json={'username': 'hacker', 'password': 'test123456'})
        assert resp.status_code == 401

    def test_update_role(self, client, admin_token):
        """更新管理员角色"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        # 先创建一个新管理员
        import uuid
        username = f'roleuser_{uuid.uuid4().hex[:6]}'
        client.post('/api/v1/admin/users',
                    json={'username': username, 'password': 'test123456', 'role': 'admin'},
                    headers=headers)
        # 获取 user_id
        list_resp = client.get('/api/v1/admin/users', headers=headers)
        target = [u for u in list_resp.json()['data']['items'] if u['username'] == username][0]
        user_id = target['id']

        resp = client.put(f'/api/v1/admin/users/{user_id}/role',
                          json={'role': 'super_admin'}, headers=headers)
        assert resp.status_code == 200
        assert resp.json()['success'] is True

    def test_update_role_nonexistent(self, client, admin_token):
        """不存在的用户更新角色应返回 404"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.put('/api/v1/admin/users/00000000-0000-0000-0000-000000000000/role',
                          json={'role': 'super_admin'}, headers=headers)
        assert resp.status_code == 404

    def test_get_settings(self, client, admin_token):
        """获取系统设置"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/admin/settings', headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert 'scenic_name' in data['data']
        assert 'business_hours' in data['data']
        assert 'welcome_message' in data['data']

    def test_update_settings(self, client, admin_token):
        """更新系统设置"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.put('/api/v1/admin/settings',
                          json={'scenic_name': '颐和园', 'maintenance_mode': True},
                          headers=headers)
        assert resp.status_code == 200
        assert resp.json()['success'] is True
        assert resp.json()['data']['scenic_name'] == '颐和园'
        assert resp.json()['data']['maintenance_mode'] is True

    def test_get_settings_no_auth(self, client):
        """未认证不可获取设置"""
        resp = client.get('/api/v1/admin/settings')
        assert resp.status_code == 401
