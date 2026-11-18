# ======================================================================================
# Application: BDC Data Portal - Cohort Builder Service
# Module: core
# Based on NHLBI Dockerfile Template V1.1
#
# Last Update by: Rudra Patel   Last Update date: 10/28/2026
# ======================================================================================
FROM nonprodnhlbi.azurecr.io/itac-base-images/openjdk21:latest
MAINTAINER BDC UNC Team <NHLBIOMITACBDCDPUNC@mail.nih.gov>
LABEL gov.nih.nhlbi.bdc.dataportal.cohortbuilder.program=bdc-data-portal-cohort-builder-service
LABEL gov.nih.nhlbi.bdc.dataportal.cohortbuilder.version=1-0-0

ENV PORT 8080
USER root
WORKDIR /var/cohort-builder
COPY ./target/bdc-cohort-builder-service.jar /var/cohort-builder/bdc-cohort-builder-service.jar
RUN curl -L -o dd-java-agent.jar https://github.com/Datadog/dd-trace-java/releases/download/download-latest/dd-java-agent.jar
EXPOSE ${PORT}
USER 1001
CMD ["java", "-javaagent:/var/cohort-builder/dd-java-agent.jar", "-jar", "bdc-cohort-builder-service.jar"]