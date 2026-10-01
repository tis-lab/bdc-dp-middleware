ARG ES_VERSION=9.5.3
FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}

# Overlay everything under es/root onto the image filesystem root.
COPY root/ /

# COPY resets existing directories to the host's owner/mode; restore what the
# base image expects (ES writes its keystore into config/ at startup).
USER root
RUN chown elasticsearch:root /usr/share/elasticsearch/config \
 && chmod 775 /usr/share/elasticsearch /usr/share/elasticsearch/config \
 && chmod -R g=u /usr/share/elasticsearch/config
USER elasticsearch
