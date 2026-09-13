# Multi-stage build for bflow-mcp — same shape as BFlow-Financial-Engine's
# Dockerfile, so it drops into the exact same ECR/ECS/CI pipeline pattern.

# Stage 1: Build stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn

RUN mvn dependency:go-offline -B

COPY src ./src

RUN ./mvnw clean package -DskipTests -B

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

RUN groupadd --system spring \
    && useradd --system --gid spring --create-home spring

COPY --from=build /app/target/*.jar app.jar

RUN chown spring:spring app.jar

USER spring:spring

# Distinct from bflow-backend's 8080, so both can coexist behind the same
# ECS security group / Cloudflare Origin Rule pattern without a port clash
# if they ever land on the same host during a rolling deploy.
EXPOSE 8081

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
