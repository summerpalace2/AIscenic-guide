# 多模块 Maven 构建 + 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY ai/pom.xml ai/pom.xml
COPY ai/src ai/src
# 用镜像自带的 mvn，不依赖 mvnw（wrapper jar 被 gitignore 了）
RUN mvn clean package -pl ai -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/ai/target/scenic-guide-ai-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx1024m", "-jar", "app.jar"]
