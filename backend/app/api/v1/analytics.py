import uuid
from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse, Response
from typing import Optional
from datetime import datetime, timedelta, timezone
from sqlalchemy import func, desc, case
from sqlalchemy.orm import Session
from collections import defaultdict
from app.api.deps import get_admin_user
from app.db.session import get_db
from app.models.user import User
from app.models.analytics import ServiceLog, AnalyticsReport

router = APIRouter(prefix='/analytics', tags=['Analytics'])

def _date_trunc_expr(column, granularity: str, dialect: str):
    """根据数据库方言生成时间截断/格式化表达式"""
    if dialect == 'mysql':
        fmt = '%Y-%m-%d' if granularity == 'day' else '%Y-%m-%d %H:00:00'
        return func.date_format(column, fmt)
    elif dialect == 'postgresql':
        if granularity == 'day':
            return func.to_char(column, 'YYYY-MM-DD')
        else:
            return func.to_char(column, 'YYYY-MM-DD HH24:00:00')
    else:  # sqlite
        fmt = '%Y-%m-%d' if granularity == 'day' else '%Y-%m-%d %H:00:00'
        return func.strftime(fmt, column)

@router.get('/dashboard')
async def dashboard(period: str = Query('today'), admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    now = datetime.now(timezone.utc)
    today_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    week_ago = today_start - timedelta(days=7)
    yesterday_start = today_start - timedelta(days=1)

    # 总服务次数
    total = db.query(ServiceLog).count()
    today_count = db.query(ServiceLog).filter(ServiceLog.created_at >= today_start).count()
    week_count = db.query(ServiceLog).filter(ServiceLog.created_at >= week_ago).count()

    # 计算趋势（环比变化率）
    prev_count = db.query(ServiceLog).filter(
        ServiceLog.created_at >= yesterday_start,
        ServiceLog.created_at < today_start
    ).count()
    trend = '0%'
    if prev_count > 0:
        change = ((today_count - prev_count) / prev_count) * 100
        trend = f"{'+' if change >= 0 else ''}{change:.1f}%"

    # 当前活跃用户（最近5分钟有请求的不同会话数）
    five_min_ago = now - timedelta(minutes=5)
    current_active = db.query(func.count(func.distinct(ServiceLog.session_id))).filter(
        ServiceLog.created_at >= five_min_ago
    ).scalar() or 0

    # 今日峰值活跃（简化版，后续可优化精确度）
    peak_today = current_active

    # 热门问题TOP10
    hot_rows = db.query(
        ServiceLog.question,
        func.count().label('cnt')
    ).group_by(ServiceLog.question).order_by(
        desc(func.count())
    ).limit(10).all()
    hot_questions_top10 = [{'rank': i + 1, 'question': r[0], 'count': r[1]} for i, r in enumerate(hot_rows)]

    # 情绪分布（比率化）
    pos = db.query(ServiceLog).filter(ServiceLog.emotion == 'positive').count()
    neu = db.query(ServiceLog).filter(ServiceLog.emotion == 'neutral').count()
    neg = db.query(ServiceLog).filter(ServiceLog.emotion == 'negative').count()
    total_emo = pos + neu + neg or 1

    # 满意度趋势：按天聚合最近7天的积极率
    dialect = db.bind.dialect.name if db.bind else 'sqlite'
    period_expr = _date_trunc_expr(ServiceLog.created_at, 'day', dialect)
    trend_rows = db.query(
        period_expr.label('period'),
        func.count().label('total'),
        func.sum(case((ServiceLog.emotion == 'positive', 1), else_=0)).label('pos_count')
    ).filter(
        ServiceLog.created_at >= week_ago
    ).group_by(period_expr).order_by(period_expr).all()
    satisfaction_trend = []
    for r in trend_rows:
        rate = round(r[2] / r[1], 4) if r[1] > 0 else 0
        satisfaction_trend.append({'date': str(r[0]), 'score': rate})

    return {
        'code': 0, 'success': True, 'message': 'OK',
        'data': {
            'service_count': {
                'total': total, 'today': today_count, 'week': week_count, 'trend': trend
            },
            'active_users': {'current': current_active, 'peak_today': peak_today},
            'satisfaction_trend': satisfaction_trend,
            'hot_questions_top10': hot_questions_top10,
            'emotion_distribution': {'positive': round(pos / total_emo, 2), 'neutral': round(neu / total_emo, 2), 'negative': round(neg / total_emo, 2)}
        }
    }

@router.get('/hot-questions')
async def hot_questions(period: str = Query('today'), top_n: int = Query(10), admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    rows = db.query(
        ServiceLog.question,
        func.count().label('cnt')
    ).group_by(ServiceLog.question).order_by(
        desc(func.count())
    ).limit(top_n).all()
    data = [{'rank': i + 1, 'question': r[0], 'count': r[1]} for i, r in enumerate(rows)]
    return {'code': 0, 'success': True, 'message': 'OK', 'data': data}

@router.get('/sentiment-trend')
async def sentiment_trend(start_date: Optional[str] = None, end_date: Optional[str] = None, granularity: str = Query('day'), admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    dialect = db.bind.dialect.name if db.bind else 'sqlite'
    period_expr = _date_trunc_expr(ServiceLog.created_at, granularity, dialect)
    query = db.query(
        period_expr.label('period'),
        ServiceLog.emotion,
        func.count().label('cnt')
    )
    if start_date:
        query = query.filter(ServiceLog.created_at >= datetime.fromisoformat(start_date))
    if end_date:
        query = query.filter(ServiceLog.created_at <= datetime.fromisoformat(end_date))
    rows = query.group_by(
        period_expr,
        ServiceLog.emotion
    ).order_by(
        period_expr
    ).all()
    # 按时间聚合为 { time, positive, neutral, negative } 比率格式
    aggregated = defaultdict(dict)
    for r in rows:
        aggregated[str(r[0])][r[1]] = r[2]
    data = []
    for period, emotions in sorted(aggregated.items()):
        total = sum(emotions.values()) or 1
        data.append({
            'time': period,
            'positive': round(emotions.get('positive', 0) / total, 2),
            'neutral': round(emotions.get('neutral', 0) / total, 2),
            'negative': round(emotions.get('negative', 0) / total, 2)
        })
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'data': data}}

@router.get('/service-count')
async def service_count(start_date: Optional[str] = None, end_date: Optional[str] = None, granularity: str = Query('day'), admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    dialect = db.bind.dialect.name if db.bind else 'sqlite'
    period_expr = _date_trunc_expr(ServiceLog.created_at, granularity, dialect)
    query = db.query(
        period_expr.label('period'),
        func.count().label('cnt')
    )
    if start_date:
        query = query.filter(ServiceLog.created_at >= datetime.fromisoformat(start_date))
    if end_date:
        query = query.filter(ServiceLog.created_at <= datetime.fromisoformat(end_date))
    rows = query.group_by(
        period_expr
    ).order_by(
        period_expr
    ).all()
    data = [
        {'period': str(r[0]), 'count': r[1]}
        for r in rows
    ]
    return {'code': 0, 'success': True, 'message': 'OK', 'data': data}

@router.get('/reports')
async def list_reports(page: int = Query(1), size: int = Query(20), type: Optional[str] = None, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    query = db.query(AnalyticsReport)
    if type:
        query = query.filter(AnalyticsReport.type == type)
    total = query.count()
    items = query.order_by(AnalyticsReport.created_at.desc()).offset((page - 1) * size).limit(size).all()
    return {
        'code': 0, 'success': True, 'message': 'OK',
        'data': {
            'total': total,
            'items': [
                {
                    'id': str(r.id), 'title': r.title, 'type': r.type,
                    'period_start': r.period_start.isoformat(),
                    'period_end': r.period_end.isoformat(),
                    'status': r.status, 'created_at': r.created_at.isoformat()
                }
                for r in items
            ]
        }
    }

@router.get('/reports/{report_id}')
async def get_report(report_id: str, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    report = db.query(AnalyticsReport).filter(AnalyticsReport.id == uuid.UUID(report_id)).first()
    if not report:
        return {'code': 404, 'success': False, 'message': '报告不存在', 'data': {}}
    return {
        'code': 0, 'success': True, 'message': 'OK',
        'data': {
            'id': str(report.id), 'title': report.title, 'type': report.type,
            'period_start': report.period_start.isoformat(),
            'period_end': report.period_end.isoformat(),
            'status': report.status, 'data': report.data,
            'created_at': report.created_at.isoformat()
        }
    }

@router.get('/reports/{report_id}/export')
async def export_report(report_id: str, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    report = db.query(AnalyticsReport).filter(AnalyticsReport.id == uuid.UUID(report_id)).first()
    if not report:
        return JSONResponse(status_code=404, content={'code': 40405, 'success': False, 'message': '报告不存在'})
    from app.services.pdf_service import generate_report_pdf
    pdf = generate_report_pdf({
        'id': str(report.id),
        'title': report.title,
        'data': report.data or {}
    })
    return Response(
        content=pdf,
        media_type='application/pdf',
        headers={
            'Content-Disposition': f'attachment; filename="{report.title}.pdf"'
        }
    )
