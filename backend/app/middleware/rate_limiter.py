"""
基于内存的 IP 限流中间件
滑动窗口算法，默认 60 次/分钟
"""
import time
from collections import defaultdict
from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.types import ASGIApp


class RateLimitMiddleware(BaseHTTPMiddleware):
    """IP 限流中间件，白名单路径跳过检查"""

    def __init__(self, app: ASGIApp, calls: int = 60, period: int = 60):
        super().__init__(app)
        self.calls = calls
        self.period = period
        self._records: dict[str, list[float]] = defaultdict(list)
        self._cleanup_counter = 0
        self._whitelist = ['/health', '/docs', '/redoc', '/openapi.json',
                           '/api/v1/auth/login', '/api/v1/auth/register']

    async def dispatch(self, request: Request, call_next):
        path = request.url.path
        if path in self._whitelist or any(path.startswith(p) for p in ['/docs/', '/redoc/', '/openapi.json']):
            return await call_next(request)

        ip = request.headers.get('x-forwarded-for', request.client.host if request.client else 'unknown')
        if ',' in ip:
            ip = ip.split(',')[0].strip()

        now = time.time()
        window_start = now - self.period
        self._records[ip] = [t for t in self._records[ip] if t > window_start]

        if len(self._records[ip]) >= self.calls:
            return JSONResponse(
                status_code=429,
                content={'code': 40302, 'success': False, 'message': '频率限制，请稍后再试', 'data': None}
            )

        self._records[ip].append(now)
        # 每10次请求清理一次过期IP记录，防止内存泄漏
        self._cleanup_counter += 1
        if self._cleanup_counter % 10 == 0:
            now = time.time()
            window_start = now - self.period
            for ip in list(self._records.keys()):
                self._records[ip] = [t for t in self._records[ip] if t > window_start]
                if not self._records[ip]:
                    del self._records[ip]
        return await call_next(request)
