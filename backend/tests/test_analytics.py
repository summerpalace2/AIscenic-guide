"""
数据分析模块测试
"""
import uuid
from datetime import datetime, timezone, timedelta
from app.models.analytics import AnalyticsReport, ServiceLog


class TestAnalytics:
    """数据分析接口测试"""

    def _seed_service_log(self, db, question='测试问题', emotion='positive'):
        """辅助：插入一条服务日志"""
        log = ServiceLog(
            id=uuid.uuid4(),
            session_id=f'session_{uuid.uuid4().hex[:8]}',
            question=question,
            emotion=emotion,
            created_at=datetime.now(timezone.utc)
        )
        db.add(log)
        db.commit()

    def _seed_report(self, db, title='测试报告', type='daily'):
        """辅助：插入一条分析报告"""
        now = datetime.now(timezone.utc)
        report = AnalyticsReport(
            id=uuid.uuid4(),
            title=title,
            type=type,
            period_start=now - timedelta(days=1),
            period_end=now,
            status='completed',
            data={'summary': 'test'}
        )
        db.add(report)
        db.commit()
        return str(report.id)

    def test_dashboard_empty(self, client, admin_token):
        """空数据时仪表盘应返回完整结构"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/analytics/dashboard', headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert 'service_count' in data['data']
        assert 'active_users' in data['data']
        assert 'satisfaction_trend' in data['data']
        assert 'hot_questions_top10' in data['data']
        assert 'emotion_distribution' in data['data']

    def test_dashboard_with_data(self, client, admin_token, db_session):
        """有数据时仪表盘应正确聚合"""
        self._seed_service_log(db_session, '故宫门票多少钱', 'positive')
        self._seed_service_log(db_session, '故宫门票多少钱', 'positive')
        self._seed_service_log(db_session, '开放时间', 'neutral')

        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/analytics/dashboard', headers=headers)
        assert resp.status_code == 200
        data = resp.json()['data']
        assert data['service_count']['total'] >= 3
        assert len(data['hot_questions_top10']) >= 1
        assert data['emotion_distribution']['positive'] > 0

    def test_dashboard_no_auth(self, client):
        """未认证不可访问仪表盘"""
        resp = client.get('/api/v1/analytics/dashboard')
        assert resp.status_code == 401

    def test_hot_questions(self, client, admin_token, db_session):
        """热门问题排行"""
        self._seed_service_log(db_session, '怎么去故宫', 'positive')
        self._seed_service_log(db_session, '怎么去故宫', 'positive')
        self._seed_service_log(db_session, '怎么去故宫', 'neutral')

        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/analytics/hot-questions', params={'top_n': 5}, headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert len(data['data']) >= 1
        assert data['data'][0]['rank'] == 1
        assert data['data'][0]['question'] == '怎么去故宫'

    def test_sentiment_trend(self, client, admin_token, db_session):
        """情感趋势聚合"""
        self._seed_service_log(db_session, '测试', 'positive')
        self._seed_service_log(db_session, '测试', 'neutral')
        self._seed_service_log(db_session, '测试', 'negative')

        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/analytics/sentiment-trend', headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert 'data' in data['data']
        # 至少有一条聚合记录
        assert len(data['data']['data']) >= 1
        record = data['data']['data'][0]
        assert 'positive' in record
        assert 'neutral' in record
        assert 'negative' in record

    def test_service_count(self, client, admin_token, db_session):
        """服务次数聚合"""
        self._seed_service_log(db_session, '测试1')
        self._seed_service_log(db_session, '测试2')

        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/analytics/service-count', headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert len(data['data']) >= 1
        assert 'period' in data['data'][0]
        assert 'count' in data['data'][0]

    def test_list_reports(self, client, admin_token, db_session):
        """报告列表分页"""
        self._seed_report(db_session, '日报', 'daily')
        self._seed_report(db_session, '周报', 'weekly')

        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/analytics/reports', params={'page': 1, 'size': 10}, headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['success'] is True
        assert data['data']['total'] >= 2
        assert len(data['data']['items']) >= 2

    def test_list_reports_filter_type(self, client, admin_token, db_session):
        """按类型筛选报告"""
        self._seed_report(db_session, '日报', 'daily')
        self._seed_report(db_session, '周报', 'weekly')

        headers = {'Authorization': f'Bearer {admin_token}'}
        resp = client.get('/api/v1/analytics/reports', params={'type': 'daily'}, headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data['data']['total'] >= 1
        for item in data['data']['items']:
            assert item['type'] == 'daily'

    def test_get_report_not_found(self, client, admin_token):
        """不存在的报告应返回特定错误"""
        headers = {'Authorization': f'Bearer {admin_token}'}
        fake_id = '00000000-0000-0000-0000-000000000000'
        resp = client.get(f'/api/v1/analytics/reports/{fake_id}', headers=headers)
        assert resp.status_code == 200  # 返回 200 带错误标识
        assert resp.json()['success'] is False
