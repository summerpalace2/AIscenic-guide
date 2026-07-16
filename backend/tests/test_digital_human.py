"""
数字人配置模块测试
"""


class TestDigitalHuman:
    """数字人配置接口测试"""

    def test_get_default_config(self, client, admin_token):
        """获取数字人配置应返回默认配置"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/admin/digital-human', headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert 'appearance' in data['data']
        assert 'voice' in data['data']
        assert 'emotion_style' in data['data']

    def test_get_config_no_auth(self, client):
        """未认证不可获取数字人配置"""
        resp = client.get('/api/v1/admin/digital-human')
        assert resp.status_code == 401

    def test_get_config_forbidden(self, client, auth_token):
        """普通用户不可获取数字人配置"""
        headers = {'Authorization': f'Bearer {auth_token}'}
        resp = client.get('/api/v1/admin/digital-human', headers=headers)
        assert resp.status_code == 403

    def test_update_config(self, client, admin_token):
        """更新数字人配置应持久化"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        new_appearance = {'model_id': 'model_male_01', 'outfit': 'scholar', 'hairstyle': 'short'}
        resp = client.put('/api/v1/admin/digital-human',
                          json={'appearance': new_appearance}, headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert data['data']['appearance'] == new_appearance

        # 验证持久化
        resp2 = client.get('/api/v1/admin/digital-human', headers=headers)
        assert resp2.json()['data']['appearance'] == new_appearance

    def test_update_voice(self, client, admin_token):
        """更新语音配置"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        new_voice = {'voice_id': 'voice_professional_01', 'speed': 1.2}
        resp = client.put('/api/v1/admin/digital-human',
                          json={'voice': new_voice}, headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['data']['voice']['voice_id'] == 'voice_professional_01'
        assert data['data']['voice']['speed'] == 1.2

    def test_update_emotion_style(self, client, admin_token):
        """更新情感风格"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.put('/api/v1/admin/digital-human',
                          json={'emotion_style': 'cheerful'}, headers=headers)
        assert resp.status_code == 200
        assert resp.json()['data']['emotion_style'] == 'cheerful'

    def test_list_voices(self, client, admin_token):
        """获取音色列表"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/admin/digital-human/voices', headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert len(data['data']) >= 3
        assert 'voice_id' in data['data'][0]

    def test_list_appearances(self, client, admin_token):
        """获取外观列表"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/admin/digital-human/appearances', headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert len(data['data']) >= 2
        assert 'model_id' in data['data'][0]

    def test_update_config_no_auth(self, client):
        """未认证不可更新数字人配置"""
        resp = client.put('/api/v1/admin/digital-human',
                          json={'emotion_style': 'cheerful'})
        assert resp.status_code == 401
