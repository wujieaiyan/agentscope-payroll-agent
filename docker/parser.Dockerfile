# 独立解析进程：PDF 渲染/扫描页 OCR 与 Web 隔离，产物写共享卷；Web 只经 HMAC 内部接口调用。
FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

WORKDIR /app
# Tesseract 为容器内主档（含中文包）；RapidOCR 需 Linux 原生库，默认镜像不内置。
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-chi-sim curl \
    && rm -rf /var/lib/apt/lists/*
COPY payroll-parser/target/payroll-parser-exec.jar /app/app.jar
RUN mkdir -p /app/data /app/parsed

EXPOSE 8300
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
