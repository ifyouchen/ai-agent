# ============================================================
# 多阶段构建 Dockerfile
# Stage 1: 编译打包（maven）
# Stage 2: 运行镜像（JRE，更小更安全）
# ============================================================

# ── Stage 1: 构建 ──────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 先只复制 pom.xml，利用 Docker 层缓存加速依赖下载
COPY pom.xml .
RUN mvn dependency:go-offline -q

# 复制源码并打包（部署镜像不编译/运行测试，CI 流程单独跑）
COPY src ./src
RUN mvn package -Dmaven.test.skip=true -q

# ── Stage 2: 运行 ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 创建非 root 用户，提升安全性
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# 从构建阶段复制 jar
COPY --from=builder /build/target/ai-agent-*.jar app.jar

# 健康检查（Spring Boot Actuator）
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# JVM 调优参数（容器感知内存，G1GC，详细GC日志）
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+HeapDumpOnOutOfMemoryError \
               -XX:HeapDumpPath=/tmp/heapdump.hprof \
               -Djava.security.egd=file:/dev/./urandom \
               --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
               --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
               --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
               --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
               --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
               --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

