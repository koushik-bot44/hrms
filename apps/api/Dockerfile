# Multi-stage build for Railway (Service Root Directory = apps/api).
# Stage 1 — build the executable jar with Maven + JDK 21.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# Stage 2 — slim runtime image.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Railway injects PORT; Spring reads it via server.port=${PORT:8080}.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
