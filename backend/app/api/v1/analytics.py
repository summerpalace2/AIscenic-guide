from fastapi import APIRouter, Depends, Query
from typing import Optional
from app.api.deps import get_admin_user
from app.models.user import User

router = APIRouter(prefix='/analytics', tags=['Analytics'])

@router.get('/dashboard')
async def dashboard(period: str = Query('today'), admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'service_count': {'total': 0, 'today': 0, 'week': 0, 'trend': '0%'}, 'active_users': {'current': 0, 'peak_today': 0}, 'satisfaction_trend': [], 'hot_questions_top10': [], 'emotion_distribution': {'positive': 0, 'neutral': 0, 'negative': 0}}}

@router.get('/hot-questions')
async def hot_questions(period: str = Query('today'), top_n: int = Query(10), admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': []}

@router.get('/sentiment-trend')
async def sentiment_trend(start_date: Optional[str] = None, end_date: Optional[str] = None, granularity: str = Query('day'), admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'data': []}}

@router.get('/service-count')
async def service_count(start_date: Optional[str] = None, end_date: Optional[str] = None, granularity: str = Query('day'), admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': []}

@router.get('/reports')
async def list_reports(page: int = Query(1), size: int = Query(20), type: Optional[str] = None, admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'total': 0, 'items': []}}

@router.get('/reports/{report_id}')
async def get_report(report_id: str, admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {}}

@router.get('/reports/{report_id}/export')
async def export_report(report_id: str, admin: User = Depends(get_admin_user)):
    from fastapi.responses import Response
    return Response(content=b'mock-pdf', media_type='application/pdf')
