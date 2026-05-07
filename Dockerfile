# 多模块 Maven 构建 + 运行
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY ai/pom.xml ai/pom.xml
COPY ai/src ai/src
RUN mvnw clean package -pl ai -DskipTests

FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=build /app/ai/target/scenic-guide-ai-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx1024m", "-jar", "app.jar"]
