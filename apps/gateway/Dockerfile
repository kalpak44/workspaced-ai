# Resolves enabled box-<name> domains through the API and relays HTTP/WebSockets through
# agent-hub to the workspace nginx. Unavailable addresses get 404.html.

# Runs as uid 101 and listens on 8080, so no root and no privileged port.
FROM nginxinc/nginx-unprivileged:1.31.7-alpine
LABEL org.opencontainers.image.source="https://github.com/kalpak44/workspaced-ai"
USER root
RUN apk upgrade --no-cache
USER 101
# The image's entrypoint fills the template from the environment on start. The root
# filesystem is read-only, so the result goes to /tmp, which nginx.conf includes.
ENV NGINX_ENVSUBST_OUTPUT_DIR=/tmp \
    DNS_RESOLVER=kube-dns.kube-system.svc.cluster.local \
    API_URL=http://workspaced-ai-api:8080 \
    AGENT_HUB_URL=http://workspaced-ai-agent-hub:8080
COPY nginx.conf /etc/nginx/nginx.conf
COPY caller.js /etc/nginx/caller.js
COPY gateway.conf.template /etc/nginx/templates/gateway.conf.template
COPY 404.html /usr/share/nginx/gateway/404.html
EXPOSE 8080
