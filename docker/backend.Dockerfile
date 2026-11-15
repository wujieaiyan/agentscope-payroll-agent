# 后端镜像：只提供鉴权、会话与管理台 API，不加载模型；构建产物为可执行分类器 exec。
FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

WORKDIR /app
# 扫描件 OCR 兜底：知识库解析无文字层 PDF 时调用 tesseract。
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*
COPY payroll-backend/target/payroll-backend-exec.jar /app/app.jar
RUN mkdir -p /app/data /app/output

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
