# Two-stage build for CCA
FROM registry.access.redhat.com/ubi9/openjdk-25:latest AS build
USER 0
WORKDIR /workspace
COPY pom.xml ./
COPY src/ src/
RUN mvn --batch-mode --no-transfer-progress clean package

FROM registry.access.redhat.com/ubi9/openjdk-25-runtime:latest
USER 0
WORKDIR /deployments
COPY --from=build --chown=185:0 /workspace/target/app.jar /deployments/app.jar
RUN chmod 0644 /deployments/app.jar && chmod 0755 /deployments
USER 185
EXPOSE 8080
# The study data is parsed and held in memory at startup (roughly 130 MB of heap for the bundled
# corpus), so give the pod at least 512Mi.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "/deployments/app.jar"]
