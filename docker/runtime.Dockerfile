# 智能体运行期镜像：执行模型对话与动态团队，只对内暴露 /internal 接口。
FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

WORKDIR /app
# curl 仅用于容器 liveness 探针（/actuator/health/liveness）。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY payroll-agent/target/payroll-agent-runtime.jar /app/app.jar
RUN mkdir -p /app/data /app/output

EXPOSE 8100
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
