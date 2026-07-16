"""
请求日志中间件：记录每个请求的 method/path/status/duration
通过 Python logging 输出到 stdout（Docker 日志），不写入数据库以免影响性能
"""
import time
import logging
from fastapi import Request
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.types import ASGIApp

logger = logging.getLogger('access')

class RequestLogMiddleware(BaseHTTPMiddleware):
    def __init__(self, app: ASGIApp):
        super().__init__(app)
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter('[%(asctime)s] %(message)s', datefmt='%Y-%m-%d %H:%M:%S'))
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)

    async def dispatch(self, request: Request, call_next):
        start = time.time()
        response = await call_next(request)
        duration = int((time.time() - start) * 1000)
        logger.info(f'{request.method} {request.url.path} {response.status_code} {duration}ms')
        return response
