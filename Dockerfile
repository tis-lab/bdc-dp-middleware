# Two-stage build for CCA
FROM registry.access.redhat.com/ubi9/openjdk-25:latest AS build
USER 0
WORKDIR /workspace
COPY pom.xml ./
COPY src/ src/
RUN mvn --batch-mode --no-transfer-progress clean package -DskipTests

FROM registry.access.redhat.com/ubi9/openjdk-25-runtime:latest
USER 0
WORKDIR /deployments
COPY --from=build --chown=185:0 /workspace/target/app.jar /deployments/app.jar
RUN chmod 0644 /deployments/app.jar && chmod 0755 /deployments
USER 185
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]