# 构建阶段
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /app/target/KCsheets-1.0-SNAPSHOT.jar app.jar
# Render 会注入 PORT 环境变量，你的代码需要读取它
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
