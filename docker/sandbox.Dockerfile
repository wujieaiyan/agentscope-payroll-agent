# 沙箱调度服务：仅调用宿主机 docker CLI 启动一次性容器，本身不执行用户脚本。
FROM docker:27-cli AS cli
FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

COPY --from=cli /usr/local/bin/docker /usr/local/bin/docker

WORKDIR /app
COPY payroll-sandbox/target/payroll-sandbox.jar /app/app.jar

EXPOSE 8200
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
