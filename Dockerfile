# ============================================================
# 灵山智慧导游 — 一站式 Docker 开发/运行镜像
# 用法:
#   docker build -t scenic-guide .
#   docker run -it --rm -p 8081:8081 -v "$(pwd):/workspace" scenic-guide
# ============================================================

# ---------- 第一阶段：编译 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY ai/pom.xml ai/pom.xml
COPY backend/pom.xml backend/pom.xml
COPY ai/src ai/src
COPY backend/src backend/src
RUN mvn clean package -pl backend -am -DskipTests

# ---------- 第二阶段：运行环境 ----------
FROM maven:3.9-eclipse-temurin-17

# 1. 安装开发工具
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get install -y --no-install-recommends \
        git curl zsh vim sudo ca-certificates \
        libpq-dev build-essential \
    && apt-get clean -y \
    && rm -rf /var/lib/apt/lists/*

# 2. 创建非 root 用户
ARG USERNAME=vscode
RUN useradd -m -s /bin/bash $USERNAME 2>/dev/null; \
    echo "$USERNAME ALL=(root) NOPASSWD:ALL" > /etc/sudoers.d/$USERNAME; \
    chmod 0440 /etc/sudoers.d/$USERNAME

# 3. 设置默认 shell
RUN chsh -s /usr/bin/zsh $USERNAME 2>/dev/null || true

# 4. 验证环境
RUN java -version 2>&1 | head -1 \
    && mvn --version 2>&1 | head -2 \
    && echo 'dev tools ok'

# 5. 复制编译产物
COPY --from=build /build/backend/target/scenic-guide-backend-0.0.1-SNAPSHOT.jar /app/app.jar

# 6. 容器配置
ENTRYPOINT []
ENV HOME=/home/vscode
EXPOSE 8080 8081
WORKDIR /workspace
USER vscode

# 7. 默认命令
CMD ["sh", "-c", "echo '=== 灵山智慧导游 ==='; echo '运行: java -jar /app/app.jar --server.port=8081'; echo '重新编译: mvn install -DskipTests && java -jar backend/target/*.jar'; bash"]