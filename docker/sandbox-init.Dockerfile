# 技能初始化镜像：允许联网，供技能自带 setup.sh 下载并生成 data/。
# 构建：docker build -f docker/sandbox-init.Dockerfile -t payroll-sandbox-init:local .
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    NODE_NO_WARNINGS=1 \
    LANG=C.UTF-8 \
    TZ=Asia/Shanghai

RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 与调度服务的 --user 65534:65534 一致；工作目录由 init 容器挂载。
USER 65534:65534
WORKDIR /skill
