# syntax=docker/dockerfile:1

# ===== 阶段1：构建前端（产物输出到 backend/static） =====
FROM node:20-alpine AS web
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend/ ./
# vite.config.js 中 outDir 指向 ../backend/src/main/resources/static
RUN mkdir -p /build/backend/src/main/resources/static && npm run build

# ===== 阶段2：构建后端（并入前端静态资源，产出单 jar） =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
# 用前端构建产物覆盖静态资源目录
COPY --from=web /build/backend/src/main/resources/static ./src/main/resources/static
RUN mvn -q -B clean package -DskipTests

# ===== 阶段3：运行（alpine 自带 wget，供 compose 健康检查使用；镜像更小） =====
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN apk add --no-cache wget >/dev/null 2>&1 || true
COPY --from=build /build/backend/target/mediation-platform.jar /app/mediation-platform.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/mediation-platform.jar"]
