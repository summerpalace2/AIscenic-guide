from contextlib import asynccontextmanager
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from app.core.config import get_settings
from app.db.session import engine
from app.db.base import Base
from app.api.v1 import auth, users, knowledge, dialog, digital_human, analytics, location, admin

settings = get_settings()

@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(bind=engine)
    yield

app = FastAPI(title=settings.APP_NAME, version='1.0.0', lifespan=lifespan, docs_url='/docs', redoc_url='/redoc')

app.add_middleware(CORSMiddleware, allow_origins=['*'], allow_credentials=True, allow_methods=['*'], allow_headers=['*'])

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    return JSONResponse(status_code=500, content={'code': 50001, 'success': False, 'message': 'Internal server error', 'data': None})

@app.get('/health')
async def health_check():
    return {'code': 0, 'success': True, 'message': 'OK', 'data': 'ok'}

app.include_router(auth.router, prefix=settings.API_V1_PREFIX)
app.include_router(users.router, prefix=settings.API_V1_PREFIX)
app.include_router(knowledge.router, prefix=settings.API_V1_PREFIX)
app.include_router(dialog.router, prefix=settings.API_V1_PREFIX)
app.include_router(digital_human.router, prefix=settings.API_V1_PREFIX)
app.include_router(analytics.router, prefix=settings.API_V1_PREFIX)
app.include_router(location.router, prefix=settings.API_V1_PREFIX)
app.include_router(admin.router, prefix=settings.API_V1_PREFIX)
