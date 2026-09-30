# ===== 阶段1：构建前端（Vue3 + Vite），产物输出到后端 static =====
FROM node:20-bookworm-slim AS frontend
WORKDIR /build/frontend
# 先复制依赖清单，利用构建缓存
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install --cache /tmp/npm-cache
COPY frontend/ ./
# 产物输出到 /build/backend/src/main/resources/static
RUN npm run build

# ===== 阶段2：Maven 打包（含前端静态资源的单 jar） =====
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build
COPY backend/pom.xml ./backend/pom.xml
# 预热 Maven 依赖缓存
RUN mvn -f backend/pom.xml -q dependency:go-offline -DskipTests || true
COPY backend/ ./backend/
# 用镜像内构建出的最新前端产物覆盖 static
COPY --from=frontend /build/backend/src/main/resources/static ./backend/src/main/resources/static
RUN mvn -f backend/pom.xml -q clean package -DskipTests

# ===== 阶段3：运行（仅 JRE，镜像更小） =====
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=backend /build/backend/target/mediation-platform.jar /app/mediation-platform.jar
EXPOSE 8080
# 默认使用 H2 内存库，零外部依赖；可用 SPRING_PROFILES_ACTIVE=mysql 切换
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/mediation-platform.jar"]
