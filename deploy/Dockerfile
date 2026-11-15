FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
COPY worker-core/pom.xml worker-core/pom.xml
COPY worker-adapters/pom.xml worker-adapters/pom.xml
COPY worker-app/pom.xml worker-app/pom.xml

RUN mvn -B dependency:go-offline

COPY worker-core worker-core
COPY worker-adapters worker-adapters
COPY worker-app worker-app

RUN mvn -B clean verify

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN groupadd --system worker \
    && useradd --system \
        --gid worker \
        --home-dir /app \
        --no-create-home \
        worker

COPY --from=build \
    --chown=worker:worker \
    /workspace/worker-app/target/worker-app-0.1.0-SNAPSHOT.jar \
    app.jar

USER worker

# Background consumer: no listening port.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
