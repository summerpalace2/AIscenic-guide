# ===== 多阶段构建：Python FastAPI 生产镜像 =====
# 阶段1：构建依赖层
FROM python:3.14-slim AS builder

WORKDIR /build
COPY requirements.txt .
# 安装依赖到用户目录，便于后续复制
RUN pip install --no-cache-dir --user -r requirements.txt

# 阶段2：运行镜像
FROM python:3.14-slim

# 安装运行时工具（健康检查、调试用）
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 从构建阶段复制已安装的 Python 包
COPY --from=builder /root/.local /root/.local
ENV PATH=/root/.local/bin:$PATH

# 复制应用代码
COPY app/ ./app/
COPY scripts/ ./scripts/

# 创建上传目录和日志目录
RUN mkdir -p /app/uploads /app/logs

# 健康检查：使用已有的 /health 端点
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

EXPOSE 8000

# 使用 uvicorn 启动，关闭访问日志（生产环境建议通过反向代理记录日志）
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
