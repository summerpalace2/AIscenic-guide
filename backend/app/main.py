from contextlib import asynccontextmanager
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi import WebSocket, Query
from app.core.config import get_settings
from app.db.session import engine
from app.db.base import Base
from app.api.v1 import auth, users, knowledge, dialog, digital_human, analytics, location, admin
from app.middleware.rate_limiter import RateLimitMiddleware
from app.ws import ws_manager, handle_websocket
from app.db.session import get_db
from app.core.security import decode_token
from fastapi.staticfiles import StaticFiles
import os
import uuid

settings = get_settings()

@asynccontextmanager
async def lifespan(app: FastAPI):
    # 尝试创建数据库表，失败则打印警告（无 DB 时仍可启动）
    try:
        Base.metadata.create_all(bind=engine)
        print('[App] 数据库表已创建/同步')
    except Exception as e:
        print(f'[App] 警告: 数据库连接失败，部分功能不可用: {e}')

    # 种子数据（开发环境自动初始化）
    try:
        from app.db.session import SessionLocal
        from app.models.user import User
        from app.core.security import get_password_hash
        from scripts.seed_spots import seed as seed_spots
        seed_db = SessionLocal()
        admin_exists = seed_db.query(User).filter(User.role.in_(['admin', 'super_admin'])).first()
        if not admin_exists:
            admin_user = User(id=uuid.uuid4(), phone='13800000000', password_hash=get_password_hash('admin123'), nickname='admin', role='admin')
            seed_db.add(admin_user)
            seed_db.commit()
            print('[App] 默认管理员已创建（admin / admin123）')
        seed_spots()
        seed_db.close()
    except Exception as e:
        print(f'[App] 种子数据初始化时发生非致命错误: {e}')

    # 启动定时任务
    try:
        from app.services.scheduler import scheduler_service
        scheduler_service.start()
        print('[App] 定时任务已启动')
    except Exception as e:
        print(f'[App] 警告: 定时任务启动失败: {e}')

    yield

    # 停止定时任务
    try:
        from app.services.scheduler import scheduler_service
        scheduler_service.stop()
    except Exception:
        pass

app = FastAPI(title=settings.APP_NAME, version='1.0.0', lifespan=lifespan, docs_url='/docs', redoc_url='/redoc')

app.add_middleware(CORSMiddleware, allow_origins=['*'], allow_credentials=True, allow_methods=['*'], allow_headers=['*'])
app.add_middleware(RateLimitMiddleware, calls=60, period=60)

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    return JSONResponse(status_code=500, content={'code': 50001, 'success': False, 'message': 'Internal server error', 'data': None})

@app.get('/health')
async def health_check():
    return {'code': 0, 'success': True, 'message': 'OK', 'data': 'ok'}

@app.websocket('/ws/{user_id}')
async def websocket_endpoint(ws: WebSocket, user_id: str, token: str = Query(...)):
    """WebSocket 端点，通过 query param token 认证"""
    payload = decode_token(token)
    if not payload:
        await ws.close(code=4001)
        return
    db = next(get_db())
    try:
        await handle_websocket(ws, user_id, db)
    finally:
        db.close()

app.include_router(auth.router, prefix=settings.API_V1_PREFIX)
app.include_router(users.router, prefix=settings.API_V1_PREFIX)
app.include_router(knowledge.router, prefix=settings.API_V1_PREFIX)
app.include_router(dialog.router, prefix=settings.API_V1_PREFIX)
app.include_router(digital_human.router, prefix=settings.API_V1_PREFIX)
app.include_router(analytics.router, prefix=settings.API_V1_PREFIX)
app.include_router(location.router, prefix=settings.API_V1_PREFIX)
app.include_router(admin.router, prefix=settings.API_V1_PREFIX)

# 挂载上传目录为静态文件服务，使上传的文件可通过 /uploads 路径访问
uploads_dir = os.path.join(os.path.dirname(__file__), '..', 'uploads')
os.makedirs(uploads_dir, exist_ok=True)
app.mount('/uploads', StaticFiles(directory=uploads_dir), name='uploads')
