FROM maven:3.9.13-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY worker-core/pom.xml worker-core/pom.xml
COPY worker-adapters/pom.xml worker-adapters/pom.xml
COPY worker-app/pom.xml worker-app/pom.xml
COPY worker-core/src worker-core/src
COPY worker-adapters/src worker-adapters/src
COPY worker-app/src worker-app/src
RUN mvn -B -pl worker-app -am verify

FROM eclipse-temurin:21.0.12_8-jre
WORKDIR /app
COPY --from=build /workspace/worker-app/target/worker-app-0.1.0-SNAPSHOT.jar /app/worker.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app/worker.jar"]
