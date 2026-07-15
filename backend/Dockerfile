# ============================================================
# Elevator Monitor — 多阶段 Docker 构建
# 阶段1: Maven 编译 (JDK 8)
# 阶段2: JRE 运行 (精简镜像)
# 无需本地预装 Java/Maven，Docker 内全自动完成
# ============================================================

# ---- 阶段1：编译 ----
FROM maven:3.8-openjdk-8 AS builder

WORKDIR /build

# 先复制 pom.xml，利用 Docker 缓存层加速构建
COPY pom.xml .
RUN mvn dependency:go-offline -B -q || true

# 再复制源码并编译（跳过测试）
COPY src ./src
RUN mvn package -DskipTests -B -q

# ---- 阶段2：运行 ----
FROM eclipse-temurin:8-jre

LABEL maintainer="xhuanc <xhhuanc@gmail.com>" \
      description="Elevator Monitor Spring Boot Application" \
      version="0.1.4-SNAPSHOT"

WORKDIR /app

# Tomcat 临时文件目录
VOLUME /tmp

EXPOSE 10008

# 从编译阶段复制 JAR
COPY --from=builder /build/target/*.jar app.jar

# 启动
# -Djava.security.egd=file:/dev/./urandom  加速 Tomcat SecureRandom 初始化
ENTRYPOINT ["java", \
            "-Djava.security.egd=file:/dev/./urandom", \
            "-jar", \
            "/app/app.jar"]