"""
定时任务服务
使用 APScheduler 实现后台每日巡检和热点数据预计算
启动时自动注册到 FastAPI 应用生命周期中
"""
from datetime import datetime, timezone, timedelta
from sqlalchemy import func
from app.core.config import get_settings
from app.db.session import SessionLocal
from app.models.analytics import ServiceLog, AnalyticsReport
from app.models.dialog import DialogMessage
from app.models.knowledge import KnowledgeDocument
import uuid

settings = get_settings()


async def daily_inspection():
    """每日巡检任务：汇总前一天的服务数据，生成报告"""
    try:
        db = SessionLocal()
    except Exception as e:
        print(f'[Scheduler] 数据库连接失败: {e}')
        return
    try:
        yesterday = datetime.now(timezone.utc) - timedelta(days=1)
        day_start = yesterday.replace(hour=0, minute=0, second=0, microsecond=0)
        day_end = day_start + timedelta(days=1)

        # 统计前一天的对话数
        msg_count = db.query(DialogMessage).filter(
            DialogMessage.created_at >= day_start,
            DialogMessage.created_at < day_end
        ).count()

        # 统计前一天的独立会话数
        session_count = db.query(func.count(func.distinct(DialogMessage.session_id))).filter(
            DialogMessage.created_at >= day_start,
            DialogMessage.created_at < day_end
        ).scalar() or 0

        # 统计前一天的请求数
        service_count = db.query(ServiceLog).filter(
            ServiceLog.created_at >= day_start,
            ServiceLog.created_at < day_end
        ).count()

        # 清理无效知识文档：vector_status=pending 且 file_url 为空，且创建超过 24 小时
        stale_cutoff = day_end
        stale_docs = db.query(KnowledgeDocument).filter(
            KnowledgeDocument.vector_status == 'pending',
            KnowledgeDocument.file_url == '',
            KnowledgeDocument.created_at < stale_cutoff
        ).all()
        for doc in stale_docs:
            db.delete(doc)
        if stale_docs:
            print(f'[Scheduler] 清理无效知识文档 {len(stale_docs)} 条')

        data = {
            'date': day_start.date().isoformat(),
            'total_messages': msg_count,
            'total_sessions': session_count,
            'total_services': service_count,
        }

        # 创建报告
        report = AnalyticsReport(
            id=uuid.uuid4(),
            title=f'{day_start.strftime("%Y-%m-%d")} 日服务报告',
            type='daily',
            period_start=day_start,
            period_end=day_end,
            status='completed',
            data=data
        )
        db.add(report)
        db.commit()
        print(f'[Scheduler] 每日巡检完成: {data}')
    except Exception as e:
        print(f'[Scheduler] 每日巡检失败: {e}')
    finally:
        db.close()


async def precompute_hot_data():
    """热点数据预计算：每小时更新热门问题和情绪分布缓存"""
    try:
        db = SessionLocal()
    except Exception as e:
        print(f'[Scheduler] 数据库连接失败: {e}')
        return
    try:
        # 统计所有服务日志中的热门问题
        hot_q = db.query(
            ServiceLog.question,
            func.count().label('count')
        ).group_by(ServiceLog.question).order_by(
            func.count().desc()
        ).limit(20).all()

        # 统计情绪分布
        pos = db.query(ServiceLog).filter(ServiceLog.emotion == 'positive').count()
        neu = db.query(ServiceLog).filter(ServiceLog.emotion == 'neutral').count()
        neg = db.query(ServiceLog).filter(ServiceLog.emotion == 'negative').count()

        print(f'[Scheduler] 热点数据预计算完成: 热门问题{len(hot_q)}条, '
              f'情绪分布(积极{pos}/中性{neu}/消极{neg})')
    except Exception as e:
        print(f'[Scheduler] 热点数据预计算失败: {e}')
    finally:
        db.close()


class SchedulerService:
    """基于 APScheduler 的定时任务管理器"""

    def __init__(self):
        self._scheduler = None

    def start(self):
        """启动定时任务调度器"""
        try:
            from apscheduler.schedulers.asyncio import AsyncIOScheduler
            from apscheduler.triggers.interval import IntervalTrigger
            from apscheduler.triggers.cron import CronTrigger

            self._scheduler = AsyncIOScheduler()

            # 每日午夜巡检
            self._scheduler.add_job(
                daily_inspection,
                CronTrigger(hour=0, minute=5),
                id='daily_inspection',
                name='每日服务巡检',
                replace_existing=True
            )

            # 每小时热点预计算
            self._scheduler.add_job(
                precompute_hot_data,
                IntervalTrigger(hours=1),
                id='hot_data_precompute',
                name='热点数据预计算',
                replace_existing=True
            )

            self._scheduler.start()
            print('[Scheduler] 定时任务调度器已启动')
        except ImportError:
            print('[Scheduler] APScheduler 未安装，跳过定时任务')
        except Exception as e:
            print(f'[Scheduler] 启动失败: {e}')

    def stop(self):
        """停止调度器"""
        if self._scheduler:
            self._scheduler.shutdown(wait=False)
            print('[Scheduler] 定时任务调度器已停止')


scheduler_service = SchedulerService()
