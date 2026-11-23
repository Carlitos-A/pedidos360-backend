# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,id=pedidos360-m2,target=/root/.m2 mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 appuser
COPY --from=build /src/target/rabbit-admin-service-*.jar app.jar
USER appuser
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
