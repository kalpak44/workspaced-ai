# Routes <name>.<domain> to the Service web-<name> the API creates for a workspace's web
# server, so no route has to be added per workspace. Whatever has no workspace behind it
# gets 404.html.

# Runs as uid 101 and listens on 8080, so no root and no privileged port.
FROM nginxinc/nginx-unprivileged:1.31.7-alpine
LABEL org.opencontainers.image.source="https://github.com/kalpak44/workspaced-ai"
USER root
RUN apk upgrade --no-cache
USER 101
# The image's entrypoint fills the template from the environment on start. The root
# filesystem is read-only, so the result goes to /tmp, which nginx.conf includes.
ENV NGINX_ENVSUBST_OUTPUT_DIR=/tmp \
    DNS_RESOLVER=kube-dns.kube-system.svc.cluster.local
COPY nginx.conf /etc/nginx/nginx.conf
COPY gateway.conf.template /etc/nginx/templates/gateway.conf.template
COPY 404.html /usr/share/nginx/gateway/404.html
EXPOSE 8080
