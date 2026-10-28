ARG PG_VERSION=18.6
FROM postgres:${PG_VERSION}

# pgvector from the PGDG apt repo that the base image already configures.
RUN apt-get update \
 && apt-get install -y --no-install-recommends "postgresql-${PG_MAJOR}-pgvector" \
 && rm -rf /var/lib/apt/lists/*

# Local development only: any role connects without a password. The entrypoint
# reads this from the environment only, so it's set here rather than in a file.
ENV POSTGRES_HOST_AUTH_METHOD=trust

# Overlay everything under pg/root onto the image filesystem root.
# /docker-entrypoint-initdb.d is root:755 in the base image too, and postgres
# only reads it, so nothing needs restoring after the COPY.
COPY root/ /
