# 第一阶段：使用 Maven 进行打包（构建环境）
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY . .
# 执行打包命令，跳过测试以加快速度
RUN mvn clean package -DskipTests

# 第二阶段：使用轻量级 JDK 运行程序（运行环境）
FROM openjdk:17-jdk-slim
WORKDIR /app

# 【关键修改点】：这里的 JAR 包名字必须和你当前项目的 pom.xml 对应
# 格式通常是：artifactId-version.jar
# 根据你之前的配置，应该是 scenic-guide-0.0.1-SNAPSHOT.jar
COPY --from=build /app/target/scenic-guide-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

# 建议加上 -Xmx 限制，防止在 2G 内存的服务器上被系统杀掉进程
ENTRYPOINT ["java", "-Xmx1024m", "-jar", "app.jar"]