# 后端镜像：只提供鉴权、会话与管理台 API，不加载模型；构建产物为可执行分类器 exec。
FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

WORKDIR /app
# curl 仅用于容器 liveness 探针（/actuator/health/liveness）。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# PDF 渲染/OCR 已由 parser 服务承担；Web 镜像不再内置 Tesseract，避免重依赖与内存争用。
COPY payroll-backend/target/payroll-backend-exec.jar /app/app.jar
RUN mkdir -p /app/data /app/parsed /app/output

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
