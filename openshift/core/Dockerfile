# ======================================================================================
# Application: BDC Data Portal - Cohort Builder Service
# Module: core
# Based on NHLBI Dockerfile Template V1.1
#
# Last Update by: Rudra Patel   Last Update date: 10/28/2026
# ======================================================================================
FROM nonprodnhlbi.azurecr.io/itac-base-images/openjdk21:latest

LABEL gov.nih.nhlbi.bdc.dataportal.cohortbuilder.program=bdc-data-portal-cohort-builder-service
LABEL gov.nih.nhlbi.bdc.dataportal.cohortbuilder.version=0-1-0

ENV PORT 8080

USER 0
RUN mkdir -p /var/cohort-builder

COPY --chown=1001:0 ./target/app.jar /var/cohort-builder/app.jar

RUN chmod -R g=u /var/cohort-builder

EXPOSE ${PORT}

USER 1001

ENTRYPOINT ["java", "-jar", "/var/cohort-builder/app.jar"]