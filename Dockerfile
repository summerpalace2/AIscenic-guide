# 多模块 Maven 构建 + 运行（backend ← ai 依赖链）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY ai/pom.xml ai/pom.xml
COPY ai/src ai/src
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
# -pl backend 构建后端模块，-am 自动构建其依赖模块 ai
RUN mvn clean package -pl backend -am -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/backend/target/scenic-guide-backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx1024m", "-jar", "app.jar"]
