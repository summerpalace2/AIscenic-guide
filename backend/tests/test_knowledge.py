"""
知识库模块测试
"""
import io

class TestKnowledgeUpload:
    """知识库上传去重测试"""

    def test_upload_file_md5_dedup(self, client, admin_token):
        """上传相同文件应触发 MD5 去重"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        file_content = b'hello scenic guide test content'

        # 第一次上传应成功
        resp = client.post(
            '/api/v1/knowledge',
            data={'title': '测试文档1', 'category': 'guide'},
            files={'file': ('test.txt', io.BytesIO(file_content), 'text/plain')},
            headers=headers
        )
        assert resp.status_code == 201
        data = resp.json()
        assert data['success'] is True

        # 上传同名文件（MD5 相同）应触发去重
        resp = client.post(
            '/api/v1/knowledge',
            data={'title': '测试文档2', 'category': 'guide'},
            files={'file': ('test.txt', io.BytesIO(file_content), 'text/plain')},
            headers=headers
        )
        assert resp.status_code == 400
        data = resp.json()
        assert data['success'] is False
        assert '文件已存在' in data['message']

    def test_upload_different_file_no_dedup(self, client, admin_token):
        """不同文件上传应互不影响"""
        headers = {'Authorization': f'Bearer {admin_token}'}

        resp = client.post(
            '/api/v1/knowledge',
            data={'title': '文档A', 'category': 'guide'},
            files={'file': ('a.txt', io.BytesIO(b'content a'), 'text/plain')},
            headers=headers
        )
        assert resp.status_code == 201

        resp = client.post(
            '/api/v1/knowledge',
            data={'title': '文档B', 'category': 'guide'},
            files={'file': ('b.txt', io.BytesIO(b'content b'), 'text/plain')},
            headers=headers
        )
        assert resp.status_code == 201

    def test_upload_no_auth(self, client):
        """未认证不可上传"""
        resp = client.post(
            '/api/v1/knowledge',
            data={'title': 'test', 'category': 'guide'},
            files={'file': ('test.txt', io.BytesIO(b'test'), 'text/plain')}
        )
        assert resp.status_code == 401


class TestKnowledgeCRUD:
    """知识库 CRUD 测试"""

    def test_create_document(self, client, admin_token):
        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.post('/api/v1/knowledge', data={
            'title': '新文档', 'category': 'guide', 'content': 'test content'
        }, headers=headers)
        assert resp.status_code == 201
        assert resp.json()['success'] is True

    def test_create_duplicate_title(self, client, admin_token):
        """重复 title 应触发去重"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        client.post('/api/v1/knowledge', data={
            'title': '重复文档', 'category': 'guide', 'content': '1'
        }, headers=headers)
        resp = client.post('/api/v1/knowledge', data={
            'title': '重复文档', 'category': 'guide', 'content': '2'
        }, headers=headers)
        assert resp.status_code == 400
        assert resp.json()['success'] is False
        assert '同名文档' in resp.json()['message']

    def test_get_nonexistent_doc(self, client, admin_token):
        """获取不存在文档应返回 404"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get(
            '/api/v1/knowledge/00000000-0000-0000-0000-000000000000',
            headers=headers
        )
        assert resp.status_code == 404
        assert resp.json()['success'] is False
