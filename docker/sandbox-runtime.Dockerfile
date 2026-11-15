# 脚本运行时镜像：python 3.12 + node（Debian 仓库版本）；不安装 pip/npm 包，运行期禁网。
# 构建：docker build -f docker/sandbox-runtime.Dockerfile -t payroll-sandbox-runtime:local .
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    NODE_NO_WARNINGS=1 \
    LANG=C.UTF-8 \
    TZ=Asia/Shanghai

RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# 与调度服务的 --user 65534:65534 一致，不保留可登录用户。
USER 65534:65534
WORKDIR /work
